package io.github.origingate.presentation;
import io.github.origingate.core.Text;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.*;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import java.time.Instant;

public final class MessagesRenderer {
    /** Colours outside the 16 named ones as §x§r§r§g§g§b§b, which 1.16+ servers and clients understand. */
    private static final LegacyComponentSerializer HEX = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private MessagesRenderer() { }
    /** Legacy text has no hover or click events. Without {@code hexColors}, colours become the nearest named one. */
    public static String legacy(Component message, boolean hexColors) {
        return (hexColors ? HEX : LegacyComponentSerializer.legacySection()).serialize(message);
    }
    /** A staff alert with the decision's caution added, such as a bypass anyone can claim in offline mode. */
    public static Component withCaution(Component alert, Decision decision) {
        if (decision.caution() == null) return alert;
        return alert.append(Component.text(" (" + decision.caution() + ")", net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }
    /** A lookup alert: {@code message} fills the {@code <message>} placeholder as plain text. */
    public static Component renderAlert(String template, String message) {
        return MiniMessage.miniMessage().deserialize(template, Placeholder.unparsed("message", message));
    }
    public static Component render(String template, LoginAttempt attempt, Decision decision) {
        IpInfo info = decision.lookup() == null ? null : decision.lookup().info();
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("username", attempt.username()),
                Placeholder.unparsed("uuid", attempt.uuid() == null ? "-" : attempt.uuid().toString()),
                Placeholder.unparsed("ip", Addresses.text(attempt.address())),
                Placeholder.unparsed("rule", decision.rule() == null ? "-" : decision.rule().id()),
                Placeholder.unparsed("time", String.valueOf(Instant.now().getEpochSecond())),
                Placeholder.unparsed("provider", Text.dash(info == null ? null : info.provider())),
                Placeholder.unparsed("organisation", Text.dash(info == null ? null : info.displayOrganisation())),
                Placeholder.unparsed("country", Text.dash(info == null ? null : info.country())),
                Placeholder.unparsed("country_code", Text.dash(info == null ? null : info.countryCode())),
                Placeholder.unparsed("city", Text.dash(info == null ? null : info.city())),
                Placeholder.unparsed("region", Text.dash(info == null ? null : info.region())),
                Placeholder.unparsed("type", Text.dash(info == null ? null : info.type())));
        return MiniMessage.miniMessage().deserialize(template, placeholders);
    }
}
