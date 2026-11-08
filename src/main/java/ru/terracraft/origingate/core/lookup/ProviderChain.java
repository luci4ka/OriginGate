package ru.terracraft.origingate.core.lookup;

import ru.terracraft.origingate.core.Alerts;
import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.Text;
import ru.terracraft.origingate.core.rules.Countries;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers one IP from the configured providers: the country job from {@code country-from}, then the VPN job
 * from {@code vpn-from}, each asking providers in order until one answers. A provider that already answered
 * in this lookup is not asked again. No new provider is asked once the time budget has passed.
 */
public final class ProviderChain {
    /** Thrown when providers answered but none had a country code. */
    public static final class NoCountryException extends LookupException {
        public NoCountryException(String message) { super(message); }
    }

    public static final class Answer {
        private final IpInfo info;
        private final AnsweredBy answeredBy;

        public Answer(IpInfo info, AnsweredBy answeredBy) {
            this.info = info;
            this.answeredBy = answeredBy;
        }

        public IpInfo info() { return info; }
        public AnsweredBy answeredBy() { return answeredBy; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Answer)) return false;
            Answer that = (Answer) other;
            return java.util.Objects.equals(info, that.info)
                    && java.util.Objects.equals(answeredBy, that.answeredBy);
        }
        @Override public int hashCode() { return java.util.Objects.hash(info, answeredBy); }
        @Override public String toString() { return "Answer[" + "info=" + info + ", " + "answeredBy=" + answeredBy + "]"; }
    }

    private static final class Named {
        private final String name;
        private final IpInfo info;

        public Named(String name, IpInfo info) {
            this.name = name;
            this.info = info;
        }

        public String name() { return name; }
        public IpInfo info() { return info; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Named)) return false;
            Named that = (Named) other;
            return java.util.Objects.equals(name, that.name)
                    && java.util.Objects.equals(info, that.info);
        }
        @Override public int hashCode() { return java.util.Objects.hash(name, info); }
        @Override public String toString() { return "Named[" + "name=" + name + ", " + "info=" + info + "]"; }
    }

    /** Failed lookups in a row (timeouts, server errors) after which staff are alerted. */
    public static final int FAILURES_BEFORE_ALERT = 3;

    /** How one provider is doing, for alerts and {@code origingate status}. */
    private static final class Health {
        /** Guarded by this. */
        private int failuresInRow;
        private String lastProblem;

        synchronized boolean failure(String problem) {
            failuresInRow++;
            lastProblem = problem;
            return failuresInRow >= FAILURES_BEFORE_ALERT;
        }

        synchronized void answered() {
            failuresInRow = 0;
        }

        synchronized String describe() {
            if (failuresInRow > 0) return "failing (" + failuresInRow + " in a row, last: " + lastProblem + ")";
            return "ok";
        }
    }

    private final List<LookupProvider> countryFrom;
    private final List<LookupProvider> vpnFrom;
    private final Duration budget;
    private final Clock clock;
    private final Log log;
    private final Alerts alerts;
    private final LookupStats stats;
    private final Map<String, Health> health = new ConcurrentHashMap<>();

    /** {@code budget} is the longest time one lookup may spend; no new provider is asked after it. */
    public ProviderChain(List<LookupProvider> countryFrom, List<LookupProvider> vpnFrom, Duration budget, Clock clock, Log log) {
        this(countryFrom, vpnFrom, budget, clock, log, Alerts.NONE, new LookupStats(clock, java.time.ZoneId.systemDefault()));
    }

    public ProviderChain(List<LookupProvider> countryFrom, List<LookupProvider> vpnFrom, Duration budget, Clock clock, Log log,
                         Alerts alerts, LookupStats stats) {
        if (countryFrom.isEmpty()) throw new IllegalArgumentException("countryFrom needs at least one provider");
        this.countryFrom = List.copyOf(countryFrom);
        this.vpnFrom = List.copyOf(vpnFrom);
        this.budget = budget;
        this.clock = clock;
        this.log = log;
        this.alerts = alerts;
        this.stats = stats;
        for (LookupProvider provider : all()) {
            health.put(provider.name(), new Health());
            provider.alertTo(alerts);
        }
    }

    /** Every provider, each once, country providers first. */
    private List<LookupProvider> all() {
        List<LookupProvider> result = new ArrayList<>(countryFrom);
        for (LookupProvider provider : vpnFrom) if (!result.contains(provider)) result.add(provider);
        return result;
    }

    public Answer lookup(String ip) throws LookupException {
        Instant deadline = clock.instant().plus(budget);
        Map<String, IpInfo> answers = new HashMap<>();
        Set<String> failed = new HashSet<>();
        List<String> problems = new ArrayList<>();
        Named country = first(ip, countryFrom, true, answers, failed, problems, deadline);
        if (country == null) {
            String message = "No provider returned a country for " + ip + ": " + String.join("; ", problems);
            // Every provider that answered had no country, so asking again soon would likely give the same.
            if (!answers.isEmpty()) throw new NoCountryException(message);
            throw new LookupException(message);
        }
        if (vpnFrom.isEmpty()) return new Answer(merge(ip, country.info(), null), new AnsweredBy(country.name(), null));
        Named vpn = first(ip, vpnFrom, false, answers, failed, problems, deadline);
        if (vpn == null) {
            throw new LookupException("No provider answered the VPN check for " + ip + ": " + String.join("; ", problems));
        }
        return new Answer(merge(ip, country.info(), vpn.info()), new AnsweredBy(country.name(), vpn.name()));
    }

    /**
     * The first provider in {@code providers} with a usable answer, or null. Failures are added to {@code problems},
     * and a provider that already failed in this lookup is not asked again.
     */
    private Named first(String ip, List<LookupProvider> providers, boolean needsCountry, Map<String, IpInfo> answers,
                        Set<String> failed, List<String> problems, Instant deadline) {
        for (LookupProvider provider : providers) {
            String name = provider.name();
            if (failed.contains(name)) continue;
            IpInfo info = answers.get(name);
            if (info == null) {
                Instant now = clock.instant();
                Health state = health.get(name);
                if (!now.isBefore(deadline)) {
                    problems.add("no time left to ask " + name);
                    return null;
                }
                log.debug("Asking " + name + " about " + ip);
                stats.requested(name);
                try {
                    info = provider.lookup(ip, deadline);
                } catch (LookupException | RuntimeException ex) {
                    String problem = Text.message(ex);
                    if (state.failure(problem)) {
                        alerts.problem("provider " + name, name + " failed " + FAILURES_BEFORE_ALERT + " lookups in a row, "
                                + "last: " + problem + ". Lookups it would answer fail meanwhile and follow on-lookup-failure.");
                    }
                    problems.add(name + ": " + problem);
                    failed.add(name);
                    continue;
                }
                state.answered();
                alerts.recovered("provider " + name, name + " answers lookups again.");
                answers.put(name, info);
            }
            if (needsCountry && !Countries.normalize(info.countryCode()).isPresent()) {
                problems.add(name + " had no country");
                continue;
            }
            return new Named(name, info);
        }
        return null;
    }

    /** Country fields from the country answer; flags, type, and operator from the VPN answer; network fields from either. */
    static IpInfo merge(String ip, IpInfo country, IpInfo vpn) {
        String code = Countries.normalize(country.countryCode()).orElseThrow(java.util.NoSuchElementException::new);
        if (vpn == null) {
            return new IpInfo(ip, country.provider(), country.organisation(), null, country.city(), country.region(),
                    country.country(), code, country.asn(), false, false, null, country.checkedAt());
        }
        return new IpInfo(ip, or(vpn.provider(), country.provider()), or(vpn.organisation(), country.organisation()),
                vpn.operatorName(), country.city(), country.region(), country.country(), code,
                or(vpn.asn(), country.asn()), vpn.vpn(), vpn.proxy(), vpn.type(), country.checkedAt());
    }

    private static String or(String first, String second) {
        return first != null ? first : second;
    }

    /** False when {@code vpn-from} is empty, so answers carry no real VPN or proxy flags. */
    public boolean checksVpn() {
        return !vpnFrom.isEmpty();
    }

    /** One line per provider for {@code origingate status}, followed by any paused API keys. */
    public List<String> status() {
        List<String> lines = new ArrayList<>();
        for (LookupProvider provider : all()) {
            String name = provider.name();
            lines.add(name + ": " + health.get(name).describe() + ", " + stats.requestsToday(name) + " requests today");
            for (String key : provider.keyStatus(clock.instant())) lines.add("  " + key);
        }
        return lines;
    }

    /** For the startup line: "country from maxmind | vpn from asn". */
    public String describe() {
        return "country from " + names(countryFrom) + " | vpn from " + (vpnFrom.isEmpty() ? "none" : names(vpnFrom));
    }

    private static String names(List<LookupProvider> providers) {
        return String.join(", ", providers.stream().map(LookupProvider::name).collect(java.util.stream.Collectors.toList()));
    }
}