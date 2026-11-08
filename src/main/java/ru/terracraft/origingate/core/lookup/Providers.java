package ru.terracraft.origingate.core.lookup;

import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.Alerts;
import ru.terracraft.origingate.core.config.OriginGateConfig;
import ru.terracraft.origingate.core.lookup.asn.AsnProvider;
import ru.terracraft.origingate.core.lookup.asn.AsnUpdater;
import ru.terracraft.origingate.core.lookup.maxmind.MaxMindProvider;
import ru.terracraft.origingate.core.lookup.maxmind.MaxMindUpdater;

import ru.terracraft.origingate.core.net.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the providers named in {@code country-from} and {@code vpn-from}, and runs the MaxMind and ASN updaters
 * when auto-update is on. Closed with the runtime.
 */
public final class Providers implements AutoCloseable {
    private final ProviderChain chain;
    private final MaxMindProvider maxmind;
    private final AsnProvider asn;
    private final ScheduledExecutorService updates;

    private Providers(ProviderChain chain, MaxMindProvider maxmind, AsnProvider asn, ScheduledExecutorService updates) {
        this.chain = chain;
        this.maxmind = maxmind;
        this.asn = asn;
        this.updates = updates;
    }

    public static Providers start(OriginGateConfig.Lookup settings, HttpTransport http, Clock clock, Log log, String userAgent) {
        return start(settings, http, clock, log, userAgent, Alerts.NONE,
                new LookupStats(clock, java.time.ZoneId.systemDefault()));
    }

    public static Providers start(OriginGateConfig.Lookup settings, HttpTransport http, Clock clock, Log log, String userAgent,
                                  Alerts alerts, LookupStats stats) {
        Map<String, LookupProvider> built = new LinkedHashMap<>();
        for (String name : settings.inUse()) built.put(name, build(name, settings, clock, log));

        MaxMindProvider maxmind = built.get("maxmind") instanceof MaxMindProvider ? (MaxMindProvider) built.get("maxmind") : null;
        AsnProvider asn = built.get("asn") instanceof AsnProvider ? (AsnProvider) built.get("asn") : null;
        ScheduledExecutorService updates = null;
        if (maxmind != null || asn != null) {
            updates = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "OriginGate Database Update");
                thread.setDaemon(true);
                return thread;
            });
        }
        OriginGateConfig.MaxMind files = settings.maxmind();
        if (maxmind != null) {
            maxmind.reload();
            if (files.autoUpdate()) {
                MaxMindUpdater updater = new MaxMindUpdater(http, files.edition(), maxmind, files.file(), log, userAgent);
                updates.scheduleWithFixedDelay(updater::runSafely, 0, MaxMindUpdater.CHECK_EVERY.toHours(), TimeUnit.HOURS);
            } else {
                maxmind.staleWarning().ifPresent(warning -> log.warn(warning, null));
            }
        }
        if (asn != null) {
            asn.reload();
            if (files.autoUpdate()) {
                AsnUpdater updater = new AsnUpdater(http, asn, files.file().resolveSibling("GeoLite2-ASN.mmdb"), log, userAgent);
                updates.scheduleWithFixedDelay(updater::runSafely, 0, AsnUpdater.CHECK_EVERY.toHours(), TimeUnit.HOURS);
            }
        }
        ProviderChain chain = new ProviderChain(pick(built, settings.countryFrom()), pick(built, settings.vpnFrom()),
                Duration.ofMillis(settings.waitMillis()), clock, log, alerts, stats);
        return new Providers(chain, maxmind, asn, updates);
    }

    private static LookupProvider build(String name, OriginGateConfig.Lookup settings, Clock clock, Log log) {
        switch (name) {
            case "maxmind": return new MaxMindProvider(settings.maxmind().file(), clock, log);
            case "asn": return new AsnProvider(settings.maxmind().file().resolveSibling("GeoLite2-ASN.mmdb"), clock, log);
            default: throw new IllegalArgumentException("Unknown provider " + name);
        }
    }

    private static List<LookupProvider> pick(Map<String, LookupProvider> built, List<String> names) {
        return names.stream().map(built::get).collect(java.util.stream.Collectors.toList());
    }

    public ProviderChain chain() {
        return chain;
    }

    /** Stops database downloads at once, so the runtime that replaces this one can take over the files. */
    public void stopUpdates() {
        if (updates != null) updates.shutdownNow();
    }

    @Override public void close() {
        stopUpdates();
        if (maxmind != null) maxmind.close();
        if (asn != null) asn.close();
    }
}