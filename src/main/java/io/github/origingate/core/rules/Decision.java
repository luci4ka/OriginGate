package io.github.origingate.core.rules;

import io.github.origingate.core.lookup.LookupService;

/**
 * The result of a check. {@code rule} is null when no rule matched. {@code lookup} is null when no
 * lookup was needed or it failed. In dry-run mode a DENY decision is logged but {@link #kicks()} is false.
 */
public final class Decision {
    private final Outcome outcome;
    private final Rule rule;
    private final String note;
    private final LookupService.Result lookup;
    private final boolean dryRun;
    private final String caution;

    public Decision(Outcome outcome, Rule rule, String note, LookupService.Result lookup, boolean dryRun) {
        this(outcome, rule, note, lookup, dryRun, null);
    }

    /** {@code caution} is a short warning for the log line and staff alert, or null. */
    public Decision(Outcome outcome, Rule rule, String note, LookupService.Result lookup, boolean dryRun, String caution) {
        this.outcome = outcome;
        this.rule = rule;
        this.note = note;
        this.lookup = lookup;
        this.dryRun = dryRun;
        this.caution = caution;
    }

    public Outcome outcome() { return outcome; }
    public Rule rule() { return rule; }
    public String note() { return note; }
    public LookupService.Result lookup() { return lookup; }
    public boolean dryRun() { return dryRun; }
    /** Set when a player got in through a name or permission bypass on a server where anyone can claim any name. */
    public String caution() { return caution; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Decision)) return false;
        Decision that = (Decision) other;
        return java.util.Objects.equals(outcome, that.outcome)
                && java.util.Objects.equals(rule, that.rule)
                && java.util.Objects.equals(note, that.note)
                && java.util.Objects.equals(lookup, that.lookup)
                && java.util.Objects.equals(dryRun, that.dryRun)
                && java.util.Objects.equals(caution, that.caution);
    }
    @Override public int hashCode() { return java.util.Objects.hash(outcome, rule, note, lookup, dryRun, caution); }
    @Override public String toString() { return "Decision[" + "outcome=" + outcome + ", " + "rule=" + rule + ", " + "note=" + note + ", " + "lookup=" + lookup + ", " + "dryRun=" + dryRun + ", " + "caution=" + caution + "]"; }


    public enum Outcome {
        /** No rule matched, or a global bypass applied. */
        ALLOW,
        /** A rule matched and the player has one of its bypass permissions. */
        BYPASS,
        /** A rule matched. */
        DENY
    }

    public boolean kicks() {
        return outcome == Outcome.DENY && !dryRun;
    }
}
