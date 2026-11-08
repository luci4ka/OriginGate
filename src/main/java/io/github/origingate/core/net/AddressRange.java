package io.github.origingate.core.net;

import java.net.InetAddress;
import java.util.Arrays;

/** A single IP address or a CIDR range such as {@code 203.0.113.0/24}. */
public final class AddressRange {
    private final String text;
    private final byte[] network;
    private final int prefix;

    private AddressRange(String text, byte[] network, int prefix) {
        this.text = text;
        this.network = network;
        this.prefix = prefix;
    }

    /** @throws IllegalArgumentException when the value is not an IP literal or CIDR range */
    public static AddressRange parse(String value) {
        String trimmed = value == null ? "" : value.trim();
        int slash = trimmed.indexOf('/');
        String host = slash < 0 ? trimmed : trimmed.substring(0, slash);
        InetAddress address = Addresses.parse(host)
                .orElseThrow(() -> new IllegalArgumentException("Not an IP address or CIDR range: " + value));
        byte[] bytes = address.getAddress();
        int bits = bytes.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            String suffix = trimmed.substring(slash + 1);
            if (!suffix.matches("\\d{1,3}")) throw new IllegalArgumentException("Invalid CIDR prefix: " + value);
            prefix = Integer.parseInt(suffix);
            if (prefix > bits) throw new IllegalArgumentException("CIDR prefix is too large: " + value);
        }
        return new AddressRange(trimmed, mask(bytes, prefix), prefix);
    }

    public boolean contains(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == network.length && Arrays.equals(mask(bytes, prefix), network);
    }

    private static byte[] mask(byte[] bytes, int prefix) {
        byte[] result = bytes.clone();
        for (int i = 0; i < result.length; i++) {
            int keep = Math.max(0, Math.min(8, prefix - i * 8));
            result[i] &= (byte) (0xFF << (8 - keep));
        }
        return result;
    }

    @Override public String toString() { return text; }
}
