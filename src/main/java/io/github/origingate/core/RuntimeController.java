package io.github.origingate.core;

import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.report.DecisionLine;
import io.github.origingate.core.rules.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import io.github.origingate.core.lookup.LookupStats;

/** Runtime lifecycle shared by server adapters. Reload is called on a worker thread. */
public final class RuntimeController implements AutoCloseable {
    /** Delivers a lookup alert to online staff. {@code template} is the MiniMessage text with a {@code <message>} placeholder. */
    public interface StaffAlerts {
        void send(OriginGateRuntime active, String template, String message);

        StaffAlerts NONE = (active, template, message) -> { };
    }

    private final Path directory;
    private final Log log;
    private final Supplier<OfflineModeWarning.Mode> mode;
    private final LookupStats stats = new LookupStats();
    private final Alerts alerts;
    private final AtomicInteger droppedReports = new AtomicInteger();
    private final ThreadPoolExecutor reports = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "OriginGate Reports");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile OriginGateRuntime runtime;
    /** Runtimes replaced by a reload that may still be closing; stopping closes them too. Guarded by this. */
    private final List<OriginGateRuntime> retired = new ArrayList<>();
    private volatile boolean closed;
    public RuntimeController(Path directory, Log log) {
        this(directory, log, () -> OfflineModeWarning.Mode.ONLINE, StaffAlerts.NONE);
    }

    /** {@code mode} tells how the platform checks player names, for the offline-mode warning. */
    public RuntimeController(Path directory, Log log, Supplier<OfflineModeWarning.Mode> mode, StaffAlerts staff) {
        this.directory = directory;
        this.log = log;
        this.mode = mode;
        // One Alerts.Once for the whole plugin, so a reload during an outage neither repeats it nor loses its recovery.
        this.alerts = new Alerts.Once() {
            @Override protected void sendProblem(String message) {
                log.warn("Alert: " + message, null);
                OriginGateRuntime active = runtime;
                if (active != null) staff.send(active, active.messages().alertLookupProblem(), message);
            }
            @Override protected void sendRecovered(String message) {
                log.info("Alert: " + message);
                OriginGateRuntime active = runtime;
                if (active != null) staff.send(active, active.messages().alertLookupRecovered(), message);
            }
        };
        reports.allowCoreThreadTimeOut(true);
    }
    public OriginGateRuntime runtime() { return runtime; }
    public synchronized String reload() {
        if (closed) return "OriginGate is stopping.";
        try {
            OriginGateRuntime.installDefaults(directory);
            OfflineModeWarning.Mode current = currentMode();
            OriginGateRuntime next = OriginGateRuntime.load(directory, log, stats, alerts, current.namesUnverified());
            if (closed) {
                next.close();
                return "OriginGate is stopping.";
            }
            OriginGateRuntime old = runtime;
            runtime = next;
            if (old != null) {
                retired.removeIf(OriginGateRuntime::isClosed);
                retired.add(old);
                old.retire();
            }
            logStarted(next, old == null ? "OriginGate is running" : "OriginGate reloaded", current);
            return "OriginGate reloaded." + (next.config().dryRun() ? " Dry run is on: nobody is kicked." : "");
        } catch (Exception | ServiceConfigurationError | LinkageError ex) {
            if (runtime == null) {
                log.error("OriginGate could not start and is NOT checking connections: " + Text.message(ex), ex);
                log.error("Fix the problem, then run: origingate reload", null);
            } else {
                log.warn("OriginGate reload failed: " + Text.message(ex), null);
            }
            return "Reload failed, " + (runtime == null ? "OriginGate is not checking connections" : "the previous settings stay active")
                    + ": " + Text.message(ex);
        }
    }
    private OfflineModeWarning.Mode currentMode() {
        try {
            return mode.get();
        } catch (RuntimeException | LinkageError ex) {
            return OfflineModeWarning.Mode.UNKNOWN;
        }
    }
    private void logStarted(OriginGateRuntime next, String prefix, OfflineModeWarning.Mode current) {
        OriginGateConfig config = next.config();
        log.info(prefix + " with " + next.storageName() + " storage" + (config.dryRun() ? " in DRY-RUN mode (nobody is kicked)" : "") + ".");
        log.info("Lookups: " + next.lookupSummary() + ".");
        if (config.lookup().uses("proxycheck") && config.lookup().proxycheck().apiKeys().isEmpty()) {
            log.warn("No proxycheck.io API keys are set; proxycheck.io allows 100 lookups per day without a key.", null);
        }
        OfflineModeWarning.check(current, config).ifPresent(warning -> log.warn(warning, null));
    }
    public void cleanup() {
        OriginGateRuntime active = runtime;
        if (active == null) return;
        try {
            OriginGateRuntime.Cleanup cleanup = active.deleteExpired();
            if (cleanup.rows() > 0 || cleanup.files() > 0) {
                log.info("Deleted " + cleanup.rows() + " expired lookups and " + cleanup.files() + " old log files");
            }
        } catch (Exception ex) {
            log.warn("Could not delete expired OriginGate data: " + Text.message(ex), null);
        }
    }
    /** Decision logging never blocks platform events or the shared lookup deadline thread. */
    public void report(OriginGateRuntime active, LoginAttempt attempt, Decision decision) {
        if (closed) return;
        if (!active.config().consoleLog().shows(decision) && (active.decisionFile() == null || decision.rule() == null)) return;
        try {
            reports.execute(() -> {
                int dropped = droppedReports.getAndSet(0);
                if (dropped > 0) log.warn("Skipped " + dropped + " connection log entries because logging could not keep up.", null);
                writeReport(active, attempt, decision);
            });
        } catch (RejectedExecutionException ex) {
            if (!closed) droppedReports.incrementAndGet();
        }
    }
    private void writeReport(OriginGateRuntime active, LoginAttempt attempt, Decision decision) {
        String line = DecisionLine.format(attempt, decision);
        if (active.config().consoleLog().shows(decision)) log.info(line);
        if (active.decisionFile() != null && decision.rule() != null) {
            try { active.decisionFile().write(line); } catch (Exception ex) { log.warn("Could not write the OriginGate log file: " + Text.message(ex), null); }
        }
    }
    /** Snapshot on the platform's allowed thread, before asynchronous rule evaluation. */
    public static Predicate<String> permissions(OriginGateRuntime active, Predicate<String> source) {
        Set<String> granted = new HashSet<>();
        for (String name : active.gate().permissionNames()) if (source.test(name)) granted.add(name);
        return granted::contains;
    }
    /**
     * Stops taking logins at once, then waits up to {@code waitMillis} for the runtime to close and queued log lines
     * to be written. The JVM exit or plugin unload that follows would cut the close short. A reload in progress keeps
     * the close waiting, and is not waited for beyond the limit.
     */
    public void stop(long waitMillis) {
        closed = true;
        reports.shutdown();
        Thread closer = new Thread(this::close, "OriginGate Shutdown");
        closer.setDaemon(true);
        closer.start();
        try {
            closer.join(waitMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
    @Override public synchronized void close() {
        closed = true;
        reports.shutdown();
        OriginGateRuntime old = runtime;
        runtime = null;
        try {
            // Every runtime stops taking lookups first, so closing waits for the slowest one rather than for each in turn.
            if (old != null) old.stopLookups();
            for (OriginGateRuntime replaced : retired) replaced.stopLookups();
            if (old != null) old.close();
            for (OriginGateRuntime replaced : retired) replaced.close();
            retired.clear();
        } finally {
            try {
                if (!reports.awaitTermination(5, TimeUnit.SECONDS)) reports.shutdownNow();
            } catch (InterruptedException ex) {
                reports.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
