package ru.terracraft.origingate.core.lookup.asn;

import com.maxmind.db.Reader;
import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.Text;
import ru.terracraft.origingate.core.lookup.IpInfo;
import ru.terracraft.origingate.core.lookup.LookupException;
import ru.terracraft.origingate.core.lookup.LookupProvider;
import ru.terracraft.origingate.core.net.Addresses;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads GeoLite2-ASN.mmdb and flags hosting/VPN networks by organisation name.
 * This is a heuristic: it catches known hosting providers (DigitalOcean, Hetzner, M247, etc.),
 * but misses VPNs that use clean residential ASNs and may false-positive on legitimate cloud users.
 */
public final class AsnProvider implements LookupProvider, AutoCloseable {
    private static final String LABEL = "asn";
    /** Lower-case fragments that mark a hosting or VPN network. */
    static final Set<String> HOSTING_KEYWORDS = Set.of(
            "hosting", "datacenter", "data center", "cloud", "vpn", "proxy", "server",
            "m247", "packethub", "digitalocean", "hetzner", "ovh", "contabo", "linode",
            "vultr", "amazon", "aws", "google cloud", "azure", "oracle", "scaleway",
            "nordvpn", "surfshark", "expressvpn", "private internet access", "cyberghost"
    );

    private final Path file;
    private final Clock clock;
    private final Log log;
    private final AtomicReference<Reader> reader = new AtomicReference<>();

    public AsnProvider(Path file, Clock clock, Log log) {
        this.file = file;
        this.clock = clock;
        this.log = log;
    }

    @Override public String name() { return "asn"; }

    public boolean reload() {
        if (!Files.isRegularFile(file)) {
            log.warn("The ASN file " + file + " does not exist yet. ASN checks are skipped until it does.", null);
            return false;
        }
        try {
            Reader opened = new Reader(file.toFile());
            close(reader.getAndSet(opened));
            log.info("Loaded ASN database from " + file.getFileName());
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Cannot read the ASN file " + file + ": " + Text.message(ex), null);
            return false;
        }
    }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        Reader current = reader.get();
        if (current == null) throw new LookupException("the ASN file is not loaded");
        InetAddress address = Addresses.parse(ip).orElseThrow(() -> new LookupException("not an IP address: " + ip));
        Map<?, ?> record;
        try {
            record = current.get(address, Map.class);
        } catch (IOException | RuntimeException ex) {
            throw new LookupException("ASN lookup failed: " + Text.message(ex), ex);
        }
        if (record == null) throw new LookupException("the ASN file has no entry for " + ip);
        String org = record.get("autonomous_system_organization") instanceof String
                ? (String) record.get("autonomous_system_organization") : null;
        String asn = record.get("autonomous_system_number") instanceof Number
                ? "AS" + record.get("autonomous_system_number") : null;
        boolean hosting = org != null && HOSTING_KEYWORDS.stream()
                .anyMatch(keyword -> org.toLowerCase(Locale.ROOT).contains(keyword));
        return new IpInfo(ip, org, null, null, null, null, null, null, asn,
                hosting, false, hosting ? IpInfo.HOSTING : null, clock.instant());
    }

    @Override public void close() {
        close(reader.getAndSet(null));
    }

    private static void close(Reader old) {
        if (old == null) return;
        try { old.close(); } catch (IOException ignored) { }
    }
}