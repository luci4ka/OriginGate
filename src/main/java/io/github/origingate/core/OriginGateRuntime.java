package io.github.origingate.core;

import io.github.origingate.core.config.ConfigException;
import io.github.origingate.core.config.ConfigLoader;
import io.github.origingate.core.config.Messages;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.lookup.MemoryCache;
import io.github.origingate.core.lookup.Providers;
import io.github.origingate.core.report.DecisionFile;
import io.github.origingate.core.rules.Gate;
import io.github.origingate.core.storage.IpStorage;
import io.github.origingate.core.storage.SqlIpStorage;
import io.github.origingate.core.storage.SqliteUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import io.github.origingate.core.net.HttpTransport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import io.github.origingate.core.lookup.LookupStats;

/** Everything built from one config: storage, lookups, rules, and the decision log. Replaced on reload. */
public final class OriginGateRuntime implements AutoCloseable {
    public static final String VERSION = BuildInfo.VERSION;
    private static final int WORKERS = 4;
    private static final int QUEUE = 256;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z");

    private final OriginGateConfig config;
    private final Messages messages;
    private final IpStorage storage;
    private final HttpTransport http;
    private final Providers providers;
    private final ThreadPoolExecutor workers;
    private final Gate gate;
    private final DecisionFile decisionFile;
    private final Clock clock;
    private final LookupStats stats;
    /** False once replaced or closed, so a retired runtime sends no more alerts. */
    private final AtomicBoolean current;
    /** "sqlite", "mysql", or "memory-only" when SQLite cannot run on this system. */
    private final String storageName;
    /** Why storage is memory-only, or null. */
    private final String storageProblem;
    private volatile boolean closed;

    private OriginGateRuntime(OriginGateConfig config, Messages messages, IpStorage storage, HttpTransport http,
                              Providers providers, ThreadPoolExecutor workers, Gate gate, DecisionFile decisionFile,
                              Clock clock, LookupStats stats, AtomicBoolean current, String storageName,
                              String storageProblem) {
        this.config = config;
        this.messages = messages;
        this.storage = storage;
        this.http = http;
        this.providers = providers;
        this.workers = workers;
        this.gate = gate;
        this.decisionFile = decisionFile;
        this.clock = clock;
        this.stats = stats;
        this.current = current;
        this.storageName = storageName;
        this.storageProblem = storageProblem;
    }

    /** Copies the default config.yml and messages.yml when they do not exist yet. */
    public static void installDefaults(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        for (String name : new String[] {"config.yml", "messages.yml"}) {
            Path target = dataDirectory.resolve(name);
            if (Files.exists(target)) continue;
            try (InputStream input = ConfigLoader.bundled(name)) {
                if (input == null) throw new IOException("Missing bundled " + name);
                Files.copy(input, target);
            }
        }
    }

    public static OriginGateRuntime load(Path dataDirectory, Log baseLog) throws ConfigException, SQLException {
        return load(dataDirectory, baseLog, new LookupStats(), Alerts.NONE);
    }

    /** {@code stats} outlives reloads; {@code alerts} receives lookup problems while this runtime is the active one. */
    public static OriginGateRuntime load(Path dataDirectory, Log baseLog, LookupStats stats, Alerts alerts)
            throws ConfigException, SQLException {
        return load(dataDirectory, baseLog, stats, alerts, false);
    }

    /** {@code namesUnverified}: anyone may join with any name, so name and permission bypasses get a caution. */
    public static OriginGateRuntime load(Path dataDirectory, Log baseLog, LookupStats stats, Alerts alerts,
                                         boolean namesUnverified) throws ConfigException, SQLException {
        OriginGateConfig config = ConfigLoader.load(dataDirectory);
        Messages messages = Messages.load(dataDirectory);
        Log log = Log.withDebug(baseLog, config.consoleLog() == OriginGateConfig.ConsoleLog.DEBUG);
        Clock clock = Clock.systemDefaultZone();
        OriginGateConfig.Storage settings = config.storage();
        IpStorage storage;
        String storageName = settings.type();
        String storageProblem = null;
        if (settings.type().equals("mysql")) {
            SqlIpStorage mysql = SqlIpStorage.mysql(settings.mysql());
            if (!mysql.ready()) {
                log.warn("The database cannot be reached. OriginGate keeps checking connections without saved lookups "
                        + "and sets up the table once the database answers.", null);
            }
            storage = mysql;
        } else {
            try {
                storage = SqlIpStorage.sqlite(settings.sqliteFile());
            } catch (SQLException ex) {
                // Stopping here would leave every login unchecked, so checks go on without saved lookups.
                boolean unavailable = ex instanceof SqliteUnavailableException;
                log.error((unavailable ? ex.getMessage()
                        : "Could not open the SQLite file " + settings.sqliteFile() + ": " + Text.message(ex)
                                + ". Check that the file is not damaged or in use and that the folder can be written, "
                                + "then run origingate reload.")
                        + " Until then, OriginGate keeps checking connections, but lookups are kept in memory only and "
                        + "asked again after a restart.", null);
                storage = IpStorage.NONE;
                storageName = "memory-only";
                storageProblem = unavailable ? "SQLite cannot run on this system" : "the SQLite file could not be opened";
            }
        }
        HttpTransport http = HttpTransport.newBuilder()
                .connectTimeout(Duration.ofMillis(config.lookup().waitMillis()))
                .build();
        ThreadPoolExecutor workers = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE), threads(), new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        Duration maxAge = Duration.ofDays(settings.maxAgeDays());
        AtomicBoolean current = new AtomicBoolean(true);
        Alerts whileCurrent = new Alerts() {
            @Override public void problem(String key, String message) { if (current.get()) alerts.problem(key, message); }
            @Override public void recovered(String key, String message) { if (current.get()) alerts.recovered(key, message); }
        };
        Providers providers = Providers.start(config.lookup(), http, clock, log, "OriginGate/" + VERSION, whileCurrent, stats);
        LookupService lookups = new LookupService(providers.chain(), storage,
                new MemoryCache(settings.memoryCacheSize(), maxAge, clock), maxAge, workers, clock, log,
                config.lookup().maxLookupsPerMinute(), stats, whileCurrent,
                // Only answers that a rule acts on are kept for a whole IPv6 /64; clean answers stay per address.
                info -> Gate.match(config.rules(), info).isPresent());
        DecisionFile decisionFile = config.logFile() ? new DecisionFile(dataDirectory.resolve("logs"), clock) : null;
        return new OriginGateRuntime(config, messages, storage, http, providers, workers, new Gate(config, lookups, log, namesUnverified),
                decisionFile, clock, stats, current, storageName, storageProblem);
    }

    private static java.util.concurrent.ThreadFactory threads() {
        AtomicInteger count = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "OriginGate Worker #" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final class Cleanup {
        private final int rows;
        private final int files;

        public Cleanup(int rows, int files) {
            this.rows = rows;
            this.files = files;
        }

        public int rows() { return rows; }
        public int files() { return files; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Cleanup)) return false;
            Cleanup that = (Cleanup) other;
            return java.util.Objects.equals(rows, that.rows)
                && java.util.Objects.equals(files, that.files);
        }
        @Override public int hashCode() { return java.util.Objects.hash(rows, files); }
        @Override public String toString() { return "Cleanup[" + "rows=" + rows + ", " + "files=" + files + "]"; }
 }

    /** Deletes stored lookups older than {@code keep-days} and log files older than {@code log-file-keep-days}. Blocks. */
    public Cleanup deleteExpired() throws SQLException, IOException {
        int keepDays = config.storage().keepDays();
        int rows = keepDays == 0 ? 0 : storage.deleteOlderThan(clock.instant().minus(Duration.ofDays(keepDays)));
        int logDays = config.logFileKeepDays();
        int files = decisionFile == null || logDays == 0 ? 0 : decisionFile.deleteOlderThan(logDays);
        return new Cleanup(rows, files);
    }

    /** Forgets one IP, or every IP with "all", in memory and in OriginGate's own table. */
    public CompletableFuture<String> clearCache(String target) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (target.equalsIgnoreCase("all")) {
                    int memory = gate.lookups().forgetAll();
                    int rows = storage.deleteAll();
                    return "Cleared " + memory + " lookups from memory and " + rows + " from storage.";
                }
                boolean memory = gate.lookups().forget(target);
                int rows = storage.delete(target);
                return "Cleared " + target + " from " + (memory ? "memory and " : "") + rows + " storage rows.";
            } catch (SQLException ex) {
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, workers);
    }

    public OriginGateConfig config() { return config; }

    /** "sqlite", "mysql", or "memory-only" when SQLite cannot run on this system. */
    public String storageName() { return storageName; }

    public Messages messages() { return messages; }

    public Gate gate() { return gate; }

    /** The lines of {@code origingate status}: lookups since the plugin started, the budget, and each provider. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        long answered = stats.memory() + stats.storage() + stats.provider();
        int rate = stats.hitRatePercent();
        lines.add("Lookups since " + TIME.format(stats.since().atZone(ZoneId.systemDefault())) + ": " + answered
                + " answered (memory " + stats.memory() + ", storage " + stats.storage() + ", providers " + stats.provider()
                + "), cache hit rate " + (rate < 0 ? "-" : rate + "%"));
        lines.add("Not answered: " + stats.failed() + " failed, " + stats.dropped() + " dropped after every login "
                + "stopped waiting, " + stats.overBudget() + " over the budget");
        LookupService lookups = gate.lookups();
        lines.add("Budget: " + (lookups.budgetPerMinute() == 0 ? "no limit (max-lookups-per-minute: 0)"
                : lookups.budgetUsed() + " of " + lookups.budgetPerMinute() + " lookups in the last minute"));
        lines.add("Memory cache: " + lookups.cache().size() + " entries. Storage: " + storageName
                + (storageProblem == null ? "" : " (" + storageProblem + ")"));
        lines.add("Providers (" + providers.chain().describe() + "):");
        for (String line : providers.chain().status()) lines.add("  " + line);
        lines.add("On lookup failure: " + config.lookup().onFailure().name().toLowerCase(java.util.Locale.ROOT)
                + ", over the budget: " + config.lookup().onBudgetExceeded().name().toLowerCase(java.util.Locale.ROOT)
                + (config.dryRun() ? ", dry run is on" : ""));
        return lines;
    }

    /** For the startup line, for example "country from maxmind, proxycheck | vpn from proxycheck". */
    public String lookupSummary() { return providers.chain().describe(); }

    /** Null when {@code log-file: false}. */
    public DecisionFile decisionFile() { return decisionFile; }

    boolean isClosed() { return closed; }

    /** Refuses new lookups at once; {@link #close} still waits for the queued ones. */
    void stopLookups() { workers.shutdown(); }

    /**
     * For reload: keeps this runtime working long enough for logins that already started with it
     * (up to {@code wait-millis} plus two requests), then closes it in the background.
     */
    public void retire() {
        current.set(false);
        providers.stopUpdates();
        long graceMillis = config.lookup().waitMillis() + 2L * config.lookup().longestRequestMillis();
        Thread closer = new Thread(() -> {
            try {
                Thread.sleep(graceMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            close();
        }, "OriginGate Retire");
        closer.setDaemon(true);
        closer.start();
    }

    /** Stops taking lookups, lets queued ones finish, then closes the HTTP client. A second call waits for the first. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        current.set(false);
        workers.shutdown();
        try {
            // The HTTP client refuses new requests once shut down, so wait for queued lookups first.
            workers.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        providers.close();
        http.close();
        storage.close();
    }
}
