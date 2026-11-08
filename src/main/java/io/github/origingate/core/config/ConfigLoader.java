package io.github.origingate.core.config;

import io.github.origingate.core.config.OriginGateConfig.AddressRule;
import io.github.origingate.core.config.OriginGateConfig.BasicRule;
import io.github.origingate.core.config.OriginGateConfig.Bypass;
import io.github.origingate.core.config.OriginGateConfig.CountryMode;
import io.github.origingate.core.config.OriginGateConfig.CountryRule;
import io.github.origingate.core.config.OriginGateConfig.FailureMode;
import io.github.origingate.core.config.OriginGateConfig.IpApi;
import io.github.origingate.core.config.OriginGateConfig.IpHub;
import io.github.origingate.core.config.OriginGateConfig.IpInfoLite;
import io.github.origingate.core.config.OriginGateConfig.Lookup;
import io.github.origingate.core.config.OriginGateConfig.MaxMind;
import io.github.origingate.core.config.OriginGateConfig.Mysql;
import io.github.origingate.core.config.OriginGateConfig.NetworkRule;
import io.github.origingate.core.config.OriginGateConfig.ProxyCheck;
import io.github.origingate.core.config.OriginGateConfig.ProxyRule;
import io.github.origingate.core.config.OriginGateConfig.Rules;
import io.github.origingate.core.config.OriginGateConfig.Storage;
import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.rules.Countries;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ConfigLoader {
    private static final int MAX_LIST = 1000;
    /** Read once at startup, so a reload never opens the plugin JAR, which may be renamed or deleted by then. */
    private static final Map<String, byte[]> BUNDLED = new HashMap<>();
    static {
        for (String name : new String[] {"config.yml", "messages.yml"}) {
            try (InputStream input = ConfigLoader.class.getResourceAsStream("/" + name)) {
                if (input != null) BUNDLED.put(name, input.readAllBytes());
            } catch (IOException ex) {
                // Reported as missing where it is used.
            }
        }
    }

    private ConfigLoader() { }

    /** The default config.yml or messages.yml from the plugin JAR, or null when it is missing. */
    public static InputStream bundled(String name) {
        byte[] data = BUNDLED.get(name);
        return data == null ? null : new ByteArrayInputStream(data);
    }

    public static OriginGateConfig load(Path dataDirectory) throws ConfigException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        YamlSection config = YamlSection.load(root.resolve("config.yml"));
        config.allowOnly("config-version", "dry-run", "console-log", "lookup", "storage", "bypass", "rules", "alerts", "log-file",
                "log-file-keep-days");
        config.integer("config-version", 1, 1);
        Lookup lookup = lookup(root, config.section("lookup"));
        Rules rules = rules(config.section("rules"));
        if (lookup.vpnFrom().isEmpty()) {
            if (rules.vpn().enabled()) throw new ConfigException("lookup.vpn-from is empty, so rules.vpn must be disabled");
            if (rules.proxy().enabled()) throw new ConfigException("lookup.vpn-from is empty, so rules.proxy must be disabled");
        }
        if (rules.hosting().enabled() && !lookup.vpnFrom().contains("proxycheck") && !lookup.vpnFrom().contains("ip-api")) {
            throw new ConfigException("rules.hosting needs proxycheck or ip-api in lookup.vpn-from; the other providers "
                    + "do not report hosting networks separately");
        }
        if (rules.denyNetworks().enabled() && lookup.inUse().equals(List.of("maxmind"))) {
            throw new ConfigException("rules.deny-networks needs a provider other than maxmind; MaxMind country files have "
                    + "no ASN or organisation");
        }
        return new OriginGateConfig(config.bool("dry-run"), config.choice("console-log", OriginGateConfig.ConsoleLog.class),
                lookup, storage(root, config.section("storage")), bypass(config.section("bypass")), rules,
                alerts(config.section("alerts")), config.bool("log-file"), config.integer("log-file-keep-days", 0, 3650));
    }

    private static Lookup lookup(Path root, YamlSection lookup) throws ConfigException {
        lookup.allowOnly("skip-private-addresses", "on-lookup-failure", "wait-millis", "max-lookups-per-minute",
                "on-budget-exceeded", "country-from", "vpn-from", "proxycheck", "iphub", "ip-api", "ipinfo", "maxmind");
        // Configs from before the provider lists used proxycheck for everything.
        List<String> countryFrom = lookup.has("country-from") ? providers(lookup, "country-from") : List.of("proxycheck");
        List<String> vpnFrom = lookup.has("vpn-from") ? providers(lookup, "vpn-from") : List.of("proxycheck");
        if (countryFrom.isEmpty()) throw new ConfigException(lookup.key("country-from") + " needs at least one provider");
        for (String name : vpnFrom) {
            if (!Lookup.VPN_PROVIDERS.contains(name)) {
                throw new ConfigException(lookup.key("vpn-from") + " cannot use " + name + ": its free data has no VPN check");
            }
        }
        Set<String> listed = new LinkedHashSet<>(countryFrom);
        listed.addAll(vpnFrom);
        ProxyCheck proxycheck = wanted(lookup, "proxycheck", listed) ? proxycheck(lookup.section("proxycheck")) : null;
        IpHub iphub = wanted(lookup, "iphub", listed) ? iphub(lookup.section("iphub")) : null;
        IpApi ipApi = wanted(lookup, "ip-api", listed) ? ipApi(lookup.section("ip-api")) : null;
        IpInfoLite ipinfo = wanted(lookup, "ipinfo", listed) ? ipinfo(lookup.section("ipinfo")) : null;
        MaxMind maxmind = wanted(lookup, "maxmind", listed) ? maxmind(root, lookup.section("maxmind")) : null;
        if (listed.contains("iphub") && iphub.apiKeys().isEmpty()) {
            throw new ConfigException(lookup.key("iphub.api-keys") + " needs at least one key to use IPHub");
        }
        if (listed.contains("ipinfo") && ipinfo.token().isEmpty()) {
            throw new ConfigException(lookup.key("ipinfo.token") + " must be set to use IPinfo");
        }
        FailureMode onFailure = lookup.choice("on-lookup-failure", FailureMode.class);
        return new Lookup(lookup.bool("skip-private-addresses"), onFailure,
                lookup.integer("wait-millis", 1000, 20000), countryFrom, vpnFrom, proxycheck, iphub, ipApi, ipinfo, maxmind,
                // Added in 1.1.0. A config from before has no limit, as before.
                lookup.has("max-lookups-per-minute") ? lookup.integer("max-lookups-per-minute", 0, 100_000) : 0,
                lookup.has("on-budget-exceeded") ? lookup.choice("on-budget-exceeded", FailureMode.class) : onFailure);
    }

    /** A provider block is read when present (so typos are caught) or when a list names the provider. */
    private static boolean wanted(YamlSection lookup, String name, Set<String> listed) {
        return lookup.has(name) || listed.contains(name);
    }

    private static List<String> providers(YamlSection lookup, String name) throws ConfigException {
        List<String> result = new ArrayList<>();
        for (String value : lookup.list(name, Lookup.PROVIDERS.size())) {
            String provider = value.toLowerCase(Locale.ROOT);
            if (!Lookup.PROVIDERS.contains(provider)) {
                throw new ConfigException(lookup.key(name) + " has an unknown provider: " + value + ". Known providers: "
                        + String.join(", ", Lookup.PROVIDERS));
            }
            if (result.contains(provider)) throw new ConfigException(lookup.key(name) + " lists " + provider + " twice");
            result.add(provider);
        }
        return List.copyOf(result);
    }

    private static ProxyCheck proxycheck(YamlSection proxycheck) throws ConfigException {
        proxycheck.allowOnly("base-url", "api-keys", "request-timeout-millis");
        String base = proxycheck.text("base-url", 8, 256);
        URI baseUrl;
        try {
            baseUrl = URI.create(base.endsWith("/") ? base : base + "/");
        } catch (IllegalArgumentException ex) {
            throw new ConfigException(proxycheck.key("base-url") + " is not a valid URL", ex);
        }
        if (!("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme())) || baseUrl.getHost() == null
                || baseUrl.getQuery() != null || baseUrl.getFragment() != null) {
            throw new ConfigException(proxycheck.key("base-url") + " must be an http or https URL without a query");
        }
        List<String> keys = proxycheck.list("api-keys", 32);
        for (String key : keys) {
            if (!key.matches("[A-Za-z0-9-]{1,64}")) throw new ConfigException(proxycheck.key("api-keys") + " contains an invalid key");
        }
        return new ProxyCheck(baseUrl, keys, proxycheck.integer("request-timeout-millis", 500, 20000));
    }

    private static IpHub iphub(YamlSection iphub) throws ConfigException {
        iphub.allowOnly("api-keys", "request-timeout-millis");
        List<String> keys = iphub.list("api-keys", 32);
        for (String key : keys) {
            if (!key.matches("[A-Za-z0-9+/=_-]{1,128}")) throw new ConfigException(iphub.key("api-keys") + " contains an invalid key");
        }
        return new IpHub(keys, iphub.integer("request-timeout-millis", 500, 20000));
    }

    private static IpApi ipApi(YamlSection ipApi) throws ConfigException {
        ipApi.allowOnly("api-key", "request-timeout-millis");
        String key = ipApi.text("api-key", 0, 64);
        if (!key.matches("[A-Za-z0-9_-]*")) throw new ConfigException(ipApi.key("api-key") + " is not a valid key");
        return new IpApi(key, ipApi.integer("request-timeout-millis", 500, 20000));
    }

    private static IpInfoLite ipinfo(YamlSection ipinfo) throws ConfigException {
        ipinfo.allowOnly("token", "request-timeout-millis");
        String token = ipinfo.text("token", 0, 128);
        if (!token.matches("[A-Za-z0-9_-]*")) throw new ConfigException(ipinfo.key("token") + " is not a valid token");
        return new IpInfoLite(token, ipinfo.integer("request-timeout-millis", 500, 20000));
    }

    private static MaxMind maxmind(Path root, YamlSection maxmind) throws ConfigException {
        maxmind.allowOnly("file", "edition", "account-id", "license-key");
        Path file = inside(root, maxmind.text("file", 1, 256), maxmind.key("file"));
        String edition = maxmind.text("edition", 1, 32);
        if (!MaxMind.EDITIONS.contains(edition)) {
            throw new ConfigException(maxmind.key("edition") + " must be GeoLite2-Country or GeoLite2-City");
        }
        int accountId = maxmind.integer("account-id", 0, Integer.MAX_VALUE);
        String licenseKey = maxmind.text("license-key", 0, 128);
        if (!licenseKey.matches("[A-Za-z0-9_]*")) throw new ConfigException(maxmind.key("license-key") + " is not a valid license key");
        if ((accountId == 0) != licenseKey.isEmpty()) {
            throw new ConfigException(maxmind.key("account-id") + " and " + maxmind.key("license-key")
                    + " must both be set, or both be left as 0 and \"\"");
        }
        return new MaxMind(file, edition, accountId, licenseKey);
    }

    private static Storage storage(Path root, YamlSection storage) throws ConfigException {
        storage.allowOnly("type", "max-age-days", "keep-days", "memory-cache-size", "sqlite", "mysql");
        String type = storage.text("type", 1, 16).toLowerCase(Locale.ROOT);
        if (!type.equals("sqlite") && !type.equals("mysql")) throw new ConfigException(storage.key("type") + " must be sqlite or mysql");
        YamlSection sqlite = storage.section("sqlite");
        sqlite.allowOnly("file");
        Path sqliteFile = inside(root, sqlite.text("file", 1, 256), sqlite.key("file"));

        Mysql mysql = null;
        if (storage.has("mysql")) {
            YamlSection section = storage.section("mysql");
            section.allowOnly("host", "port", "database", "username", "password", "ssl-mode", "connect-timeout-millis",
                    "socket-timeout-millis");
            String host = section.text("host", 1, 255);
            if (!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\\[[0-9A-Fa-f:]{2,45}]")) throw new ConfigException(section.key("host") + " is not a valid host");
            String database = section.text("database", 1, 64);
            if (!database.matches("[A-Za-z0-9_]{1,64}")) throw new ConfigException(section.key("database") + " may contain only letters, numbers, and _");
            String sslMode = section.text("ssl-mode", 1, 16);
            if (!Set.of("verify-full", "verify-ca", "disable").contains(sslMode)) {
                throw new ConfigException(section.key("ssl-mode") + " must be verify-full, verify-ca, or disable");
            }
            mysql = new Mysql(host, section.integer("port", 1, 65535), database, section.text("username", 1, 80),
                    section.text("password", 0, 1024), sslMode, section.integer("connect-timeout-millis", 100, 30000),
                    section.integer("socket-timeout-millis", 100, 30000));
        }
        if (type.equals("mysql") && mysql == null) throw new ConfigException("Missing setting: " + storage.key("mysql"));
        int maxAgeDays = storage.integer("max-age-days", 1, 365);
        int keepDays = storage.integer("keep-days", 0, 3650);
        if (keepDays != 0 && keepDays < maxAgeDays) {
            throw new ConfigException(storage.key("keep-days") + " must be 0 or at least max-age-days (" + maxAgeDays + ")");
        }
        return new Storage(type, sqliteFile, type.equals("mysql") ? mysql : null,
                maxAgeDays, keepDays, storage.integer("memory-cache-size", 100, 1_000_000));
    }

    private static Bypass bypass(YamlSection bypass) throws ConfigException {
        bypass.allowOnly("permissions", "players", "addresses");
        Set<String> players = new LinkedHashSet<>();
        for (String player : bypass.list("players", MAX_LIST)) players.add(player.toLowerCase(Locale.ROOT));
        return new Bypass(permissions(bypass, "permissions"), Set.copyOf(players), ranges(bypass, "addresses"));
    }

    private static Rules rules(YamlSection rules) throws ConfigException {
        rules.allowOnly("deny-addresses", "deny-networks", "vpn", "proxy", "hosting", "country");
        YamlSection deny = rules.section("deny-addresses");
        deny.allowOnly("enabled", "list", "bypass-permissions");
        YamlSection vpn = rules.section("vpn");
        vpn.allowOnly("enabled", "bypass-permissions");
        YamlSection proxy = rules.section("proxy");
        proxy.allowOnly("enabled", "allowed-countries", "bypass-permissions");
        YamlSection country = rules.section("country");
        country.allowOnly("enabled", "mode", "countries", "bypass-permissions");
        return new Rules(
                new AddressRule(deny.bool("enabled"), ranges(deny, "list"), permissions(deny, "bypass-permissions")),
                denyNetworks(rules),
                new BasicRule(vpn.bool("enabled"), permissions(vpn, "bypass-permissions")),
                new ProxyRule(proxy.bool("enabled"), countries(proxy, "allowed-countries"), permissions(proxy, "bypass-permissions")),
                hosting(rules),
                new CountryRule(country.bool("enabled"), country.choice("mode", CountryMode.class),
                        countries(country, "countries"), permissions(country, "bypass-permissions")));
    }

    /** Added in 1.1.0; a config from before has no such section, which means the rule is off. */
    private static BasicRule hosting(YamlSection rules) throws ConfigException {
        if (!rules.has("hosting")) return new BasicRule(false, List.of());
        YamlSection hosting = rules.section("hosting");
        hosting.allowOnly("enabled", "bypass-permissions");
        return new BasicRule(hosting.bool("enabled"), permissions(hosting, "bypass-permissions"));
    }

    /** Added in 1.1.0; a config from before has no such section, which means the rule is off. */
    private static NetworkRule denyNetworks(YamlSection rules) throws ConfigException {
        List<String> none = List.of();
        if (!rules.has("deny-networks")) return new NetworkRule(false, none, none, none);
        YamlSection section = rules.section("deny-networks");
        section.allowOnly("enabled", "asns", "organisations", "bypass-permissions");
        List<String> asns = new ArrayList<>();
        for (String value : section.list("asns", MAX_LIST)) {
            String asn = value.toUpperCase(Locale.ROOT);
            if (!asn.startsWith("AS")) asn = "AS" + asn;
            if (!asn.matches("AS[0-9]{1,10}")) {
                throw new ConfigException(section.key("asns") + " needs AS numbers such as AS64496; not a valid one: " + value);
            }
            asns.add(asn);
        }
        List<String> organisations = new ArrayList<>();
        for (String value : section.list("organisations", MAX_LIST)) {
            if (value.length() < 3) {
                throw new ConfigException(section.key("organisations") + " entries need at least 3 characters: " + value);
            }
            organisations.add(value.toLowerCase(Locale.ROOT));
        }
        return new NetworkRule(section.bool("enabled"), List.copyOf(asns),
                List.copyOf(organisations), permissions(section, "bypass-permissions"));
    }

    private static List<String> alerts(YamlSection alerts) throws ConfigException {
        alerts.allowOnly("permissions");
        return permissions(alerts, "permissions");
    }

    private static List<String> permissions(YamlSection section, String name) throws ConfigException {
        List<String> values = section.list(name, 100);
        for (String value : values) {
            if (!value.matches("[A-Za-z0-9_.*-]{1,128}")) throw new ConfigException(section.key(name) + " contains an invalid permission: " + value);
        }
        return values;
    }

    private static List<String> countries(YamlSection section, String name) throws ConfigException {
        List<String> result = new ArrayList<>();
        for (String value : section.list(name, 300)) {
            result.add(Countries.normalize(value).orElseThrow(() -> new ConfigException(
                    section.key(name) + " needs two-letter country codes such as US; not a known code: " + value)));
        }
        return List.copyOf(result);
    }

    private static List<AddressRange> ranges(YamlSection section, String name) throws ConfigException {
        List<AddressRange> result = new ArrayList<>();
        for (String value : section.list(name, MAX_LIST)) {
            try {
                result.add(AddressRange.parse(value));
            } catch (IllegalArgumentException ex) {
                throw new ConfigException(section.key(name) + ": " + ex.getMessage(), ex);
            }
        }
        return List.copyOf(result);
    }

    private static Path inside(Path root, String value, String key) throws ConfigException {
        Path configured;
        try {
            configured = java.nio.file.Paths.get(value);
        } catch (RuntimeException ex) {
            throw new ConfigException(key + " is not a valid path", ex);
        }
        if (configured.isAbsolute()) throw new ConfigException(key + " must be relative to the plugin folder");
        Path resolved = root.resolve(configured).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) throw new ConfigException(key + " must name a file inside the plugin folder");
        for (Path current = resolved; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new ConfigException(key + " contains a symbolic link");
        }
        return resolved;
    }
}