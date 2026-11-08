package io.github.origingate.core.report;

import io.github.origingate.core.Text;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.Decision;
import io.github.origingate.core.rules.LoginAttempt;

import java.util.Locale;

/** One log line per decision, used for both the console and the log file. */
public final class DecisionLine {
    private DecisionLine() { }

    public static String format(LoginAttempt attempt, Decision decision) {
        StringBuilder line = new StringBuilder(label(decision));
        line.append(" rule=").append(decision.rule() == null ? "-" : decision.rule().id());
        line.append(" player=").append(attempt.username());
        line.append(" uuid=").append(attempt.uuid() == null ? "-" : attempt.uuid());
        line.append(" ip=").append(Addresses.text(attempt.address()));
        if (decision.lookup() != null) {
            IpInfo info = decision.lookup().info();
            quoted(line, "provider", info.provider());
            quoted(line, "organisation", info.displayOrganisation());
            quoted(line, "country", info.country());
            line.append(" country_code=").append(info.countryCode() == null ? "-" : info.countryCode());
            quoted(line, "city", info.city());
            quoted(line, "type", info.type());
            line.append(" vpn=").append(info.vpn() ? "yes" : "no");
            line.append(" proxy=").append(info.proxy() ? "yes" : "no");
            LookupService.Result result = decision.lookup();
            line.append(" source=").append(result.answeredBy() != null
                    ? result.answeredBy().joined() : result.source().name().toLowerCase(Locale.ROOT));
        }
        if (decision.note() != null) quoted(line, "note", decision.note());
        if (decision.caution() != null) quoted(line, "caution", decision.caution());
        return line.toString();
    }

    /** ALLOW, BYPASS, DENY, or WOULD-DENY in dry-run mode. */
    public static String label(Decision decision) {
        if (decision.outcome() == Decision.Outcome.DENY && decision.dryRun()) return "WOULD-DENY";
        return decision.outcome().name();
    }

    private static void quoted(StringBuilder line, String key, String value) {
        line.append(' ').append(key).append("=\"");
        String text = Text.dash(value);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') line.append('\\');
            if (!Character.isISOControl(c)) line.append(c);
        }
        line.append('"');
    }
}
