package io.github.origingate.core;

import io.github.origingate.core.config.OriginGateConfig;

import java.util.Optional;

/**
 * Warns when the owner set name or permission bypasses on a server or proxy where anyone can claim any name.
 * In offline mode the UUID comes from the name, and permission plugins load permissions by that UUID before any
 * login plugin runs, so only {@code bypass.addresses} holds up. The per-rule bypass permissions shipped in the default
 * config are left out: nobody holds them until the owner grants them, and a bypass that is used is flagged in its
 * log line and staff alert instead (see {@link io.github.origingate.core.rules.Gate#UNVERIFIED_NAME}).
 */
public final class OfflineModeWarning {
    /** How the server or proxy OriginGate runs on learns who a player is. */
    public enum Mode {
        /** Online mode: names and UUIDs are checked with Mojang. */
        ONLINE,
        /** A backend behind Velocity modern forwarding from an online-mode proxy: the forwarded data is signed. */
        MODERN_FORWARDING,
        /** A backend behind BungeeCord IP forwarding: anyone who reaches this server's port directly can fake it. */
        LEGACY_FORWARDING,
        /** Offline mode without forwarding, or behind an offline-mode proxy: anyone can use any name. */
        OFFLINE,
        /** Offline mode, but whether a proxy forwards checked identities could not be found out. */
        UNKNOWN;

        /** True where anyone may be able to join with any name. */
        public boolean namesUnverified() {
            return this != ONLINE && this != MODERN_FORWARDING;
        }
    }

    private OfflineModeWarning() { }

    public static Optional<String> check(Mode mode, OriginGateConfig config) {
        if (!mode.namesUnverified()) return Optional.empty();
        boolean players = !config.bypass().players().isEmpty();
        boolean permissions = !config.bypass().permissions().isEmpty();
        String set = players && permissions ? "bypass.players and bypass.permissions follow"
                : players ? "bypass.players follows" : "bypass.permissions follow";
        if (mode == Mode.LEGACY_FORWARDING) {
            // Faked IP addresses skip every check, so this is worth one line even without bypasses.
            return Optional.of("BungeeCord IP forwarding (bungeecord: true) is on: anyone who can reach this server's port "
                    + "directly can fake their name, UUID, and IP address and skip every check"
                    + (players || permissions ? " (" + set + " the name)" : "")
                    + ". Let only the proxy reach this port, or use Velocity modern forwarding.");
        }
        if (!players && !permissions) return Optional.empty();
        if (mode == Mode.UNKNOWN) {
            return Optional.of("This server runs in offline mode, and OriginGate could not tell whether a proxy with Velocity "
                    + "modern forwarding checks names. If not, anyone can join with any name, and " + set
                    + " the name. Only bypass.addresses holds up in offline mode.");
        }
        return Optional.of("This server or proxy runs in offline mode, so anyone can join with any name, and " + set
                + " the name. Only bypass.addresses holds up in offline mode.");
    }
}
