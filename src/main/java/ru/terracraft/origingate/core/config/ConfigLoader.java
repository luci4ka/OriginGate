package ru.terracraft.origingate.core.config;

import ru.terracraft.origingate.core.config.OriginGateConfig.AddressRule;
import ru.terracraft.origingate.core.config.OriginGateConfig.BasicRule;
import ru.terracraft.origingate.core.config.OriginGateConfig.Bypass;
import ru.terracraft.origingate.core.config.OriginGateConfig.CountryMode;
import ru.terracraft.origingate.core.config.OriginGateConfig.CountryRule;
import ru.terracraft.origingate.core.config.OriginGateConfig.FailureMode;
import ru.terracraft.origingate.core.config.OriginGateConfig.Lookup;
import ru.terracraft.origingate.core.config.OriginGateConfig.MaxMind;
import ru.terracraft.origingate.core.config.OriginGateConfig.NetworkRule;
import ru.terracraft.origingate.core.config.OriginGateConfig.ProxyRule;
import ru.terracraft.origingate.core.config.OriginGateConfig.Rules;
import ru.terracraft.origingate.core.net.AddressRange;
import ru.terracraft.origingate.core.rules.Countries;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
        config.allowOnly("config-version", "dry-run", "console-log", "lookup", "bypass", "rules", "alerts", "log-file",
                "log-file-keep-days");
        config.integer("config-version", 1, 1);
        Lookup lookup = lookup(root, config.section("lookup"));
        Rules rules = rules(config.section("rules"));
        if (lookup.vpnFrom().isEmpty()) {
            if (rules.vpn().enabled()) throw new ConfigException("lookup.vpn-from is empty, so rules.vpn must be disabled");
            if (rules.proxy().enabled()) throw new ConfigException("lookup.vpn-from is empty, so rules.proxy must be disabled");
            if (rules.hosting().enabled()) throw new ConfigException("lookup.vpn-from is empty, so rules.hosting must be disabled");
        }
        if (rules.denyNetworks().enabled() && lookup.inUse().equals(List.of("maxmind"))) {
            throw new ConfigException("rules.deny-networks needs the asn provider; MaxMind country files have no ASN or organisation");
        }
        return new OriginGateConfig(config.bool("dry-run"), config.choice("console-log", OriginGateConfig.ConsoleLog.class),
                lookup, bypass(config.section("bypass")), rules,
                alerts(config.section("alerts")), config.bool("log-file"), config.integer("log-file-keep-days", 0, 3650));
    }

    private static Lookup lookup(Path root, YamlSection lookup) throws ConfigException {
        lookup.allowOnly("skip-private-addresses", "on-lookup-failure", "wait-millis", "max-lookups-per-minute",
                "on-budget-exceeded", "country-from", "vpn-from", "maxmind");
        List<String> countryFrom = lookup.has("country-from") ? providers(lookup, "country-from") : List.of("maxmind");
        List<String> vpnFrom = lookup.has("vpn-from") ? providers(lookup, "vpn-from") : List.of("asn");
        if (countryFrom.isEmpty()) throw new ConfigException(lookup.key("country-from") + " needs at least one provider");
        for (String name : vpnFrom) {
            if (!Lookup.VPN_PROVIDERS.contains(name)) {
                throw new ConfigException(lookup.key("vpn-from") + " cannot use " + name + ": its free data has no VPN check");
            }
        }
        MaxMind maxmind = maxmind(root, lookup.section("maxmind"));
        FailureMode onFailure = lookup.choice("on-lookup-failure", FailureMode.class);
        return new Lookup(lookup.bool("skip-private-addresses"), onFailure,
                lookup.integer("wait-millis", 1000, 20000), countryFrom, vpnFrom, maxmind,
                lookup.has("max-lookups-per-minute") ? lookup.integer("max-lookups-per-minute", 0, 100_000) : 0,
                lookup.has("on-budget-exceeded") ? lookup.choice("on-budget-exceeded", FailureMode.class) : onFailure);
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

    private static MaxMind maxmind(Path root, YamlSection maxmind) throws ConfigException {
        maxmind.allowOnly("file", "edition", "auto-update");
        Path file = inside(root, maxmind.text("file", 1, 256), maxmind.key("file"));
        String edition = maxmind.text("edition", 1, 32);
        if (!MaxMind.EDITIONS.contains(edition)) {
            throw new ConfigException(maxmind.key("edition") + " must be GeoLite2-Country or GeoLite2-City");
        }
        return new MaxMind(file, edition, !maxmind.has("auto-update") || maxmind.bool("auto-update"));
    }

    private static Bypass bypass(YamlSection bypass) throws ConfigException {
        bypass.allowOnly("permissions", "players", "addresses");
        Set<String> players = new LinkedHashSet<>();
        for (String player : bypass.list("players", MAX_LIST)) players.add(player.toLowerCase(Locale.ROOT));
        return new Bypass(permissions(bypass, "permissions"), Set.copyOf(players), ranges(bypass, "addresses"));
    }

    private static Rules rules(YamlSection rules) throws ConfigException {
        // "proxy" is accepted for old configs but is optional: no bundled provider sets proxy = true.
        rules.allowOnly("deny-addresses", "deny-networks", "vpn", "proxy", "hosting", "country");
        YamlSection deny = rules.section("deny-addresses");
        deny.allowOnly("enabled", "list", "bypass-permissions");
        YamlSection vpn = rules.section("vpn");
        vpn.allowOnly("enabled", "bypass-permissions");
        YamlSection country = rules.section("country");
        country.allowOnly("enabled", "mode", "countries", "bypass-permissions");

        ProxyRule proxy;
        if (rules.has("proxy")) {
            YamlSection section = rules.section("proxy");
            section.allowOnly("enabled", "allowed-countries", "bypass-permissions");
            proxy = new ProxyRule(section.bool("enabled"), countries(section, "allowed-countries"),
                    permissions(section, "bypass-permissions"));
        } else {
            proxy = new ProxyRule(false, List.of(), List.of());
        }

        return new Rules(
                new AddressRule(deny.bool("enabled"), ranges(deny, "list"), permissions(deny, "bypass-permissions")),
                denyNetworks(rules),
                new BasicRule(vpn.bool("enabled"), permissions(vpn, "bypass-permissions")),
                proxy,
                hosting(rules),
                new CountryRule(country.bool("enabled"), country.choice("mode", CountryMode.class),
                        countries(country, "countries"), permissions(country, "bypass-permissions")));
    }

    private static BasicRule hosting(YamlSection rules) throws ConfigException {
        if (!rules.has("hosting")) return new BasicRule(false, List.of());
        YamlSection hosting = rules.section("hosting");
        hosting.allowOnly("enabled", "bypass-permissions");
        return new BasicRule(hosting.bool("enabled"), permissions(hosting, "bypass-permissions"));
    }

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