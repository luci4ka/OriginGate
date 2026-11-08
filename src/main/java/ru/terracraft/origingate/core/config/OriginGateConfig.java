package ru.terracraft.origingate.core.config;

import ru.terracraft.origingate.core.net.AddressRange;
import ru.terracraft.origingate.core.rules.Decision;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class OriginGateConfig {
    private final boolean dryRun;
    private final ConsoleLog consoleLog;
    private final Lookup lookup;
    private final Bypass bypass;
    private final Rules rules;
    private final List<String> alertPermissions;
    private final boolean logFile;
    private final int logFileKeepDays;

    public OriginGateConfig(boolean dryRun, ConsoleLog consoleLog, Lookup lookup, Bypass bypass, Rules rules,
                            List<String> alertPermissions, boolean logFile, int logFileKeepDays) {
        this.dryRun = dryRun;
        this.consoleLog = consoleLog;
        this.lookup = lookup;
        this.bypass = bypass;
        this.rules = rules;
        this.alertPermissions = alertPermissions;
        this.logFile = logFile;
        this.logFileKeepDays = logFileKeepDays;
    }

    public boolean dryRun() { return dryRun; }
    public ConsoleLog consoleLog() { return consoleLog; }
    public Lookup lookup() { return lookup; }
    public Bypass bypass() { return bypass; }
    public Rules rules() { return rules; }
    public List<String> alertPermissions() { return alertPermissions; }
    public boolean logFile() { return logFile; }
    public int logFileKeepDays() { return logFileKeepDays; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OriginGateConfig)) return false;
        OriginGateConfig that = (OriginGateConfig) other;
        return java.util.Objects.equals(dryRun, that.dryRun)
                && java.util.Objects.equals(consoleLog, that.consoleLog)
                && java.util.Objects.equals(lookup, that.lookup)
                && java.util.Objects.equals(bypass, that.bypass)
                && java.util.Objects.equals(rules, that.rules)
                && java.util.Objects.equals(alertPermissions, that.alertPermissions)
                && java.util.Objects.equals(logFile, that.logFile)
                && java.util.Objects.equals(logFileKeepDays, that.logFileKeepDays);
    }
    @Override public int hashCode() { return java.util.Objects.hash(dryRun, consoleLog, lookup, bypass, rules, alertPermissions, logFile, logFileKeepDays); }


    /** How much the console shows per login. Each level includes the ones before it. */
    public enum ConsoleLog {
        NONE, KICKS, MATCHES, ALL, DEBUG;

        public boolean shows(Decision decision) {
            switch (this) {
                case NONE: return false;
                case KICKS: return decision.outcome() == Decision.Outcome.DENY;
                case MATCHES: return decision.rule() != null;
                case ALL: case DEBUG: return true;
                default: throw new AssertionError(this);
            }
        }
    }

    public enum FailureMode { ALLOW, DENY }

    public enum CountryMode { ALLOWLIST, DENYLIST }

    /**
     * {@code countryFrom} and {@code vpnFrom} hold provider names in the order they are asked.
     * Only {@code maxmind} and {@code asn} are supported.
     */
    public static final class Lookup {
        private final boolean skipPrivateAddresses;
        private final FailureMode onFailure;
        private final int waitMillis;
        private final List<String> countryFrom;
        private final List<String> vpnFrom;
        private final MaxMind maxmind;
        private final int maxLookupsPerMinute;
        private final FailureMode onBudgetExceeded;

        /** {@code maxLookupsPerMinute} 0 means no limit. {@code onBudgetExceeded} is {@code onFailure} when not set. */
        public Lookup(boolean skipPrivateAddresses, FailureMode onFailure, int waitMillis, List<String> countryFrom,
                      List<String> vpnFrom, MaxMind maxmind, int maxLookupsPerMinute, FailureMode onBudgetExceeded) {
            this.skipPrivateAddresses = skipPrivateAddresses;
            this.onFailure = onFailure;
            this.waitMillis = waitMillis;
            this.countryFrom = countryFrom;
            this.vpnFrom = vpnFrom;
            this.maxmind = maxmind;
            this.maxLookupsPerMinute = maxLookupsPerMinute;
            this.onBudgetExceeded = onBudgetExceeded;
        }

        public boolean skipPrivateAddresses() { return skipPrivateAddresses; }
        public FailureMode onFailure() { return onFailure; }
        public int waitMillis() { return waitMillis; }
        public List<String> countryFrom() { return countryFrom; }
        public List<String> vpnFrom() { return vpnFrom; }
        public MaxMind maxmind() { return maxmind; }
        public int maxLookupsPerMinute() { return maxLookupsPerMinute; }
        public FailureMode onBudgetExceeded() { return onBudgetExceeded; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Lookup)) return false;
            Lookup that = (Lookup) other;
            return java.util.Objects.equals(skipPrivateAddresses, that.skipPrivateAddresses)
                    && java.util.Objects.equals(onFailure, that.onFailure)
                    && java.util.Objects.equals(waitMillis, that.waitMillis)
                    && java.util.Objects.equals(countryFrom, that.countryFrom)
                    && java.util.Objects.equals(vpnFrom, that.vpnFrom)
                    && java.util.Objects.equals(maxmind, that.maxmind)
                    && maxLookupsPerMinute == that.maxLookupsPerMinute
                    && onBudgetExceeded == that.onBudgetExceeded;
        }
        @Override public int hashCode() { return java.util.Objects.hash(skipPrivateAddresses, onFailure, waitMillis, countryFrom, vpnFrom, maxmind, maxLookupsPerMinute, onBudgetExceeded); }
        @Override public String toString() { return "Lookup[" + "skipPrivateAddresses=" + skipPrivateAddresses + ", " + "onFailure=" + onFailure + ", " + "waitMillis=" + waitMillis + ", " + "countryFrom=" + countryFrom + ", " + "vpnFrom=" + vpnFrom + ", " + "maxmind=" + maxmind + ", " + "maxLookupsPerMinute=" + maxLookupsPerMinute + ", " + "onBudgetExceeded=" + onBudgetExceeded + "]"; }

        public static final List<String> PROVIDERS = List.of("maxmind", "asn");
        /** Providers whose free data has a VPN check. */
        public static final List<String> VPN_PROVIDERS = List.of("asn");

        /** Every provider in either list, each once, country-from first. */
        public List<String> inUse() {
            Set<String> names = new LinkedHashSet<>(countryFrom);
            names.addAll(vpnFrom);
            return List.copyOf(names);
        }

        public boolean uses(String name) {
            return countryFrom.contains(name) || vpnFrom.contains(name);
        }

        /** The longest request timeout among the web providers in use, or 0 when none is used. */
        public int longestRequestMillis() {
            return 0;
        }
    }

    /** {@code autoUpdate} downloads the files from GitHub Releases when the build date changes. */
    public static final class MaxMind {
        private final Path file;
        private final String edition;
        private final boolean autoUpdate;

        public MaxMind(Path file, String edition, boolean autoUpdate) {
            this.file = file;
            this.edition = edition;
            this.autoUpdate = autoUpdate;
        }

        public Path file() { return file; }
        public String edition() { return edition; }
        public boolean autoUpdate() { return autoUpdate; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof MaxMind)) return false;
            MaxMind that = (MaxMind) other;
            return java.util.Objects.equals(file, that.file)
                    && java.util.Objects.equals(edition, that.edition)
                    && autoUpdate == that.autoUpdate;
        }
        @Override public int hashCode() { return java.util.Objects.hash(file, edition, autoUpdate); }

        public static final List<String> EDITIONS = List.of("GeoLite2-Country", "GeoLite2-City");

        @Override public String toString() {
            return "MaxMind[file=" + file + ", edition=" + edition + ", autoUpdate=" + autoUpdate + "]";
        }
    }

    /** {@code players} holds lower-case names and UUID strings. */
    public static final class Bypass {
        private final List<String> permissions;
        private final Set<String> players;
        private final List<AddressRange> addresses;

        public Bypass(List<String> permissions, Set<String> players, List<AddressRange> addresses) {
            this.permissions = permissions;
            this.players = players;
            this.addresses = addresses;
        }

        public List<String> permissions() { return permissions; }
        public Set<String> players() { return players; }
        public List<AddressRange> addresses() { return addresses; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Bypass)) return false;
            Bypass that = (Bypass) other;
            return java.util.Objects.equals(permissions, that.permissions)
                    && java.util.Objects.equals(players, that.players)
                    && java.util.Objects.equals(addresses, that.addresses);
        }
        @Override public int hashCode() { return java.util.Objects.hash(permissions, players, addresses); }
        @Override public String toString() { return "Bypass[" + "permissions=" + permissions + ", " + "players=" + players + ", " + "addresses=" + addresses + "]"; }
    }

    public static final class Rules {
        private final AddressRule denyAddresses;
        private final NetworkRule denyNetworks;
        private final BasicRule vpn;
        private final ProxyRule proxy;
        private final BasicRule hosting;
        private final CountryRule country;

        public Rules(AddressRule denyAddresses, NetworkRule denyNetworks, BasicRule vpn, ProxyRule proxy, BasicRule hosting,
                     CountryRule country) {
            this.denyAddresses = denyAddresses;
            this.denyNetworks = denyNetworks;
            this.vpn = vpn;
            this.proxy = proxy;
            this.hosting = hosting;
            this.country = country;
        }

        public AddressRule denyAddresses() { return denyAddresses; }
        public NetworkRule denyNetworks() { return denyNetworks; }
        public BasicRule vpn() { return vpn; }
        public ProxyRule proxy() { return proxy; }
        public BasicRule hosting() { return hosting; }
        public CountryRule country() { return country; }

        /** Every rule's bypass permissions, in rule order. */
        public List<List<String>> bypassPermissions() {
            return List.of(denyAddresses.bypassPermissions(), denyNetworks.bypassPermissions(),
                    vpn.bypassPermissions(), proxy.bypassPermissions(), hosting.bypassPermissions(), country.bypassPermissions());
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Rules)) return false;
            Rules that = (Rules) other;
            return java.util.Objects.equals(denyAddresses, that.denyAddresses)
                    && java.util.Objects.equals(denyNetworks, that.denyNetworks)
                    && java.util.Objects.equals(vpn, that.vpn)
                    && java.util.Objects.equals(proxy, that.proxy)
                    && java.util.Objects.equals(hosting, that.hosting)
                    && java.util.Objects.equals(country, that.country);
        }
        @Override public int hashCode() { return java.util.Objects.hash(denyAddresses, denyNetworks, vpn, proxy, hosting, country); }
        @Override public String toString() { return "Rules[" + "denyAddresses=" + denyAddresses + ", " + "denyNetworks=" + denyNetworks + ", " + "vpn=" + vpn + ", " + "proxy=" + proxy + ", " + "hosting=" + hosting + ", " + "country=" + country + "]"; }
    }

    public static final class AddressRule {
        private final boolean enabled;
        private final List<AddressRange> list;
        private final List<String> bypassPermissions;

        public AddressRule(boolean enabled, List<AddressRange> list, List<String> bypassPermissions) {
            this.enabled = enabled;
            this.list = list;
            this.bypassPermissions = bypassPermissions;
        }

        public boolean enabled() { return enabled; }
        public List<AddressRange> list() { return list; }
        public List<String> bypassPermissions() { return bypassPermissions; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof AddressRule)) return false;
            AddressRule that = (AddressRule) other;
            return java.util.Objects.equals(enabled, that.enabled)
                    && java.util.Objects.equals(list, that.list)
                    && java.util.Objects.equals(bypassPermissions, that.bypassPermissions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, list, bypassPermissions); }
        @Override public String toString() { return "AddressRule[" + "enabled=" + enabled + ", " + "list=" + list + ", " + "bypassPermissions=" + bypassPermissions + "]"; }
    }

    /** {@code asns} hold upper-case numbers with the AS prefix; {@code organisations} hold lower-case text to look for. */
    public static final class NetworkRule {
        private final boolean enabled;
        private final List<String> asns;
        private final List<String> organisations;
        private final List<String> bypassPermissions;

        public NetworkRule(boolean enabled, List<String> asns, List<String> organisations, List<String> bypassPermissions) {
            this.enabled = enabled;
            this.asns = asns;
            this.organisations = organisations;
            this.bypassPermissions = bypassPermissions;
        }

        public boolean enabled() { return enabled; }
        public List<String> asns() { return asns; }
        public List<String> organisations() { return organisations; }
        public List<String> bypassPermissions() { return bypassPermissions; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof NetworkRule)) return false;
            NetworkRule that = (NetworkRule) other;
            return enabled == that.enabled
                    && java.util.Objects.equals(asns, that.asns)
                    && java.util.Objects.equals(organisations, that.organisations)
                    && java.util.Objects.equals(bypassPermissions, that.bypassPermissions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, asns, organisations, bypassPermissions); }
        @Override public String toString() { return "NetworkRule[" + "enabled=" + enabled + ", " + "asns=" + asns + ", " + "organisations=" + organisations + ", " + "bypassPermissions=" + bypassPermissions + "]"; }
    }

    public static final class BasicRule {
        private final boolean enabled;
        private final List<String> bypassPermissions;

        public BasicRule(boolean enabled, List<String> bypassPermissions) {
            this.enabled = enabled;
            this.bypassPermissions = bypassPermissions;
        }

        public boolean enabled() { return enabled; }
        public List<String> bypassPermissions() { return bypassPermissions; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BasicRule)) return false;
            BasicRule that = (BasicRule) other;
            return java.util.Objects.equals(enabled, that.enabled)
                    && java.util.Objects.equals(bypassPermissions, that.bypassPermissions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, bypassPermissions); }
        @Override public String toString() { return "BasicRule[" + "enabled=" + enabled + ", " + "bypassPermissions=" + bypassPermissions + "]"; }
    }

    /** Proxies from {@code allowedCountries} (ISO codes) are not blocked. */
    public static final class ProxyRule {
        private final boolean enabled;
        private final List<String> allowedCountries;
        private final List<String> bypassPermissions;

        public ProxyRule(boolean enabled, List<String> allowedCountries, List<String> bypassPermissions) {
            this.enabled = enabled;
            this.allowedCountries = allowedCountries;
            this.bypassPermissions = bypassPermissions;
        }

        public boolean enabled() { return enabled; }
        public List<String> allowedCountries() { return allowedCountries; }
        public List<String> bypassPermissions() { return bypassPermissions; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ProxyRule)) return false;
            ProxyRule that = (ProxyRule) other;
            return java.util.Objects.equals(enabled, that.enabled)
                    && java.util.Objects.equals(allowedCountries, that.allowedCountries)
                    && java.util.Objects.equals(bypassPermissions, that.bypassPermissions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, allowedCountries, bypassPermissions); }
        @Override public String toString() { return "ProxyRule[" + "enabled=" + enabled + ", " + "allowedCountries=" + allowedCountries + ", " + "bypassPermissions=" + bypassPermissions + "]"; }
    }

    public static final class CountryRule {
        private final boolean enabled;
        private final CountryMode mode;
        private final List<String> countries;
        private final List<String> bypassPermissions;

        public CountryRule(boolean enabled, CountryMode mode, List<String> countries, List<String> bypassPermissions) {
            this.enabled = enabled;
            this.mode = mode;
            this.countries = countries;
            this.bypassPermissions = bypassPermissions;
        }

        public boolean enabled() { return enabled; }
        public CountryMode mode() { return mode; }
        public List<String> countries() { return countries; }
        public List<String> bypassPermissions() { return bypassPermissions; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CountryRule)) return false;
            CountryRule that = (CountryRule) other;
            return java.util.Objects.equals(enabled, that.enabled)
                    && java.util.Objects.equals(mode, that.mode)
                    && java.util.Objects.equals(countries, that.countries)
                    && java.util.Objects.equals(bypassPermissions, that.bypassPermissions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(enabled, mode, countries, bypassPermissions); }
        @Override public String toString() { return "CountryRule[" + "enabled=" + enabled + ", " + "mode=" + mode + ", " + "countries=" + countries + ", " + "bypassPermissions=" + bypassPermissions + "]"; }
    }
}