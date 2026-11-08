package io.github.origingate.core.rules;

import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** A player connection being checked. {@code uuid} may be null for an IP-only check. */
public final class LoginAttempt {
    private final String username;
    private final UUID uuid;
    private final InetAddress address;
    private final Predicate<String> permissions;

    public LoginAttempt(String username, UUID uuid, InetAddress address, Predicate<String> permissions) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(permissions, "permissions");
        this.username = username;
        this.uuid = uuid;
        this.address = address;
        this.permissions = permissions;
    }

    public String username() { return username; }
    public UUID uuid() { return uuid; }
    public InetAddress address() { return address; }
    public Predicate<String> permissions() { return permissions; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LoginAttempt)) return false;
        LoginAttempt that = (LoginAttempt) other;
        return java.util.Objects.equals(username, that.username)
                && java.util.Objects.equals(uuid, that.uuid)
                && java.util.Objects.equals(address, that.address)
                && java.util.Objects.equals(permissions, that.permissions);
    }
    @Override public int hashCode() { return java.util.Objects.hash(username, uuid, address, permissions); }
    @Override public String toString() { return "LoginAttempt[" + "username=" + username + ", " + "uuid=" + uuid + ", " + "address=" + address + ", " + "permissions=" + permissions + "]"; }



    /** An IP-only check: no player, no permissions. */
    public static LoginAttempt address(InetAddress address) {
        return new LoginAttempt("-", null, address, permission -> false);
    }

    public boolean hasAny(Iterable<String> permissionList) {
        for (String permission : permissionList) if (permissions.test(permission)) return true;
        return false;
    }
}
