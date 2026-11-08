package io.github.origingate.core.lookup;

import io.github.origingate.core.Log;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.lookup.maxmind.MaxMindProvider;
import io.github.origingate.core.lookup.maxmind.MaxMindUpdater;

import io.github.origingate.core.net.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Builds the providers named in {@code country-from} and {@code vpn-from}, and runs the MaxMind updater when a
 * license key is set. Only listed providers are built. Closed with the runtime.
 */
public final class Providers implements AutoCloseable {
    private final ProviderChain chain;
    private final MaxMindProvider maxmind;
    private final ScheduledExecutorService updates;

    private Providers(ProviderChain chain, MaxMindProvider maxmind, ScheduledExecutorService updates) {
        this.chain = chain;
        this.maxmind = maxmind;
        this.updates = updates;
    }

    public static Providers start(OriginGateConfig.Lookup settings, HttpTransport http, Clock clock, Log log, String userAgent) {
        return start(settings, http, clock, log, userAgent, io.github.origingate.core.Alerts.NONE,
                new LookupStats(clock, java.time.ZoneId.systemDefault()));
    }

    public static Providers start(OriginGateConfig.Lookup settings, HttpTransport http, Clock clock, Log log, String userAgent,
                                  io.github.origingate.core.Alerts alerts, LookupStats stats) {
        Map<String, LookupProvider> built = new LinkedHashMap<>();
        for (String name : settings.inUse()) built.put(name, build(name, settings, http, clock, log, userAgent));

        MaxMindProvider maxmind = built.get("maxmind") instanceof MaxMindProvider ? (MaxMindProvider) built.get("maxmind") : null;
        ScheduledExecutorService updates = null;
        if (maxmind != null) {
            OriginGateConfig.MaxMind files = settings.maxmind();
            maxmind.reload();
            if (files.autoUpdate()) {
                MaxMindUpdater updater = new MaxMindUpdater(http, MaxMindUpdater.DOWNLOAD_URL, files.edition(),
                        files.accountId(), files.licenseKey(), maxmind, files.file(), log, userAgent);
                updates = Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "OriginGate MaxMind Update");
                    thread.setDaemon(true);
                    return thread;
                });
                updates.scheduleWithFixedDelay(updater::runSafely, 0, MaxMindUpdater.CHECK_EVERY.toHours(), TimeUnit.HOURS);
            } else {
                maxmind.staleWarning().ifPresent(warning -> log.warn(warning, null));
            }
        }
        if (settings.uses("ip-api") && !settings.ipApi().pro()) {
            log.warn("ip-api is used without a key: player IPs are sent over plain HTTP, and the free service does not "
                    + "allow commercial use.", null);
        }
        ProviderChain chain = new ProviderChain(pick(built, settings.countryFrom()), pick(built, settings.vpnFrom()),
                Duration.ofMillis(settings.waitMillis()), clock, log, alerts, stats);
        return new Providers(chain, maxmind, updates);
    }

    private static LookupProvider build(String name, OriginGateConfig.Lookup settings, HttpTransport http, Clock clock, Log log,
                                        String userAgent) {
        switch (name) {
            case "proxycheck": return new ProxyCheckProvider(http, settings.proxycheck().baseUrl(), settings.proxycheck().apiKeys(),
                    millis(settings.proxycheck().requestTimeoutMillis()), clock, log, userAgent);
            case "iphub": return new IpHubProvider(http, IpHubProvider.BASE_URL, settings.iphub().apiKeys(),
                    millis(settings.iphub().requestTimeoutMillis()), clock, log, userAgent);
            case "ip-api": return new IpApiProvider(http, settings.ipApi().pro() ? IpApiProvider.PRO_URL : IpApiProvider.FREE_URL,
                    settings.ipApi().apiKey(), millis(settings.ipApi().requestTimeoutMillis()), clock, userAgent);
            case "ipinfo": return new IpInfoProvider(http, IpInfoProvider.BASE_URL, settings.ipinfo().token(),
                    millis(settings.ipinfo().requestTimeoutMillis()), clock, userAgent);
            case "maxmind": return new MaxMindProvider(settings.maxmind().file(), clock, log);
            default: throw new IllegalArgumentException("Unknown provider " + name);
        }
    }

    private static Duration millis(int value) {
        return Duration.ofMillis(value);
    }

    private static List<LookupProvider> pick(Map<String, LookupProvider> built, List<String> names) {
        return names.stream().map(built::get).collect(java.util.stream.Collectors.toList());
    }

    public ProviderChain chain() {
        return chain;
    }

    /** Stops MaxMind downloads at once, so the runtime that replaces this one can take over the file. */
    public void stopUpdates() {
        if (updates != null) updates.shutdownNow();
    }

    @Override public void close() {
        stopUpdates();
        if (maxmind != null) maxmind.close();
    }
}
