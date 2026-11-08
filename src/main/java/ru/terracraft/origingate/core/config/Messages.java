package ru.terracraft.origingate.core.config;

import ru.terracraft.origingate.core.rules.Rule;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** MiniMessage templates from messages.yml. Rendering is left to the platform. */
public final class Messages {
    private final Map<Rule, String> kicks;
    private final String bypassNotice;
    private final String alertDenied;
    private final String alertBypassed;
    private final String alertLookupProblem;
    private final String alertLookupRecovered;

    public Messages(Map<Rule, String> kicks, String bypassNotice, String alertDenied, String alertBypassed,
                    String alertLookupProblem, String alertLookupRecovered) {
        this.kicks = Map.copyOf(kicks);
        this.bypassNotice = bypassNotice;
        this.alertDenied = alertDenied;
        this.alertBypassed = alertBypassed;
        this.alertLookupProblem = alertLookupProblem;
        this.alertLookupRecovered = alertLookupRecovered;
    }

    public Map<Rule, String> kicks() { return kicks; }
    public String bypassNotice() { return bypassNotice; }
    public String alertDenied() { return alertDenied; }
    public String alertBypassed() { return alertBypassed; }
    /** For lookup problems such as a paused provider. The text is in the {@code <message>} placeholder. */
    public String alertLookupProblem() { return alertLookupProblem; }
    public String alertLookupRecovered() { return alertLookupRecovered; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Messages)) return false;
        Messages that = (Messages) other;
        return java.util.Objects.equals(kicks, that.kicks)
                && java.util.Objects.equals(bypassNotice, that.bypassNotice)
                && java.util.Objects.equals(alertDenied, that.alertDenied)
                && java.util.Objects.equals(alertBypassed, that.alertBypassed)
                && java.util.Objects.equals(alertLookupProblem, that.alertLookupProblem)
                && java.util.Objects.equals(alertLookupRecovered, that.alertLookupRecovered);
    }
    @Override public int hashCode() { return java.util.Objects.hash(kicks, bypassNotice, alertDenied, alertBypassed, alertLookupProblem, alertLookupRecovered); }
    @Override public String toString() { return "Messages[" + "kicks=" + kicks + ", " + "bypassNotice=" + bypassNotice + ", " + "alertDenied=" + alertDenied + ", " + "alertBypassed=" + alertBypassed + ", " + "alertLookupProblem=" + alertLookupProblem + ", " + "alertLookupRecovered=" + alertLookupRecovered + "]"; }

    public String kick(Rule rule) {
        return kicks.get(rule);
    }

    /**
     * Messages added after 1.0.0 (the hosting and deny-networks kicks, the lookup alerts) are optional: a messages.yml
     * from before uses the bundled text for them.
     */
    public static Messages load(Path dataDirectory) throws ConfigException {
        YamlSection messages = YamlSection.load(dataDirectory.resolve("messages.yml"));
        YamlSection defaults = YamlSection.resource("messages.yml");
        messages.allowOnly("kick", "bypass-notice", "alerts");
        YamlSection kick = messages.section("kick");
        YamlSection defaultKick = defaults.section("kick");
        Map<Rule, String> kicks = new EnumMap<>(Rule.class);
        String[] names = new String[Rule.values().length];
        for (Rule rule : Rule.values()) names[rule.ordinal()] = rule.id();
        kick.allowOnly(names);
        for (Rule rule : Rule.values()) kicks.put(rule, optional(kick, defaultKick, rule.id(), 1, 4096, rule.addedLater()));
        YamlSection alerts = messages.section("alerts");
        YamlSection defaultAlerts = defaults.section("alerts");
        alerts.allowOnly("denied", "bypassed", "lookup-problem", "lookup-recovered");
        return new Messages(kicks, messages.text("bypass-notice", 0, 1024), alerts.text("denied", 0, 1024),
                alerts.text("bypassed", 0, 1024), optional(alerts, defaultAlerts, "lookup-problem", 0, 1024, true),
                optional(alerts, defaultAlerts, "lookup-recovered", 0, 1024, true));
    }

    private static String optional(YamlSection section, YamlSection defaults, String name, int min, int max,
                                   boolean mayBeMissing) throws ConfigException {
        if (mayBeMissing && !section.has(name)) return defaults.text(name, min, max);
        return section.text(name, min, max);
    }
}