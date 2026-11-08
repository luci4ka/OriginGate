package ru.terracraft.origingate.core.lookup.maxmind;


import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.Text;
import ru.terracraft.origingate.core.lookup.IpInfo;
import ru.terracraft.origingate.core.lookup.LookupException;
import ru.terracraft.origingate.core.lookup.LookupProvider;
import ru.terracraft.origingate.core.net.Addresses;

import java.io.IOException;
import java.net.InetAddress;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads a MaxMind GeoLite2 Country or City file, for the country job only. The file is read into memory,
 * so it can be replaced on disk while in use (a memory-mapped file stays locked on Windows).
 */
public final class MaxMindProvider implements LookupProvider, AutoCloseable {
    /** MaxMind's license asks for updates within 30 days of a new release. */
    public static final Duration STALE_AFTER = Duration.ofDays(30);
    private final Path file;
    private final Clock clock;
    private final Log log;
    private final AtomicReference<MaxMindDatabase> reader = new AtomicReference<>();

    public MaxMindProvider(Path file, Clock clock, Log log) {
        this.file = file;
        this.clock = clock;
        this.log = log;
    }

    @Override public String name() { return "maxmind"; }

    /** Opens the file, or replaces the one in use. Returns false and keeps the current one when it cannot be read. */
    public boolean reload() {
        if (!Files.isRegularFile(file)) {
            log.warn("The MaxMind file " + file + " does not exist yet. MaxMind is skipped until it does.", null);
            return false;
        }
        try {
            MaxMindDatabase opened = new MaxMindDatabase(file);
            close(reader.getAndSet(opened));
            log.info("Loaded MaxMind " + opened.databaseType() + " built on "
                    + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(opened.buildTime()));
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("Cannot read the MaxMind file " + file + ": " + Text.message(ex), null);
            return false;
        }
    }

    /** When the file in use was built, from its metadata, or empty when no file is loaded. */
    public Optional<Instant> buildTime() {
        MaxMindDatabase current = reader.get();
        return current == null ? Optional.empty() : Optional.of(current.buildTime());
    }

    /** The edition of the file in use from its metadata, for example "GeoLite2-City", or empty when none is loaded. */
    public Optional<String> databaseType() {
        MaxMindDatabase current = reader.get();
        return current == null ? Optional.empty() : Optional.of(current.databaseType());
    }

    /** A warning when the file in use is older than {@link #STALE_AFTER}, for owners who update it themselves. */
    public Optional<String> staleWarning() {
        if (!buildTime().isPresent()) return Optional.empty();
        Instant built = buildTime().get();
        if (!built.isBefore(clock.instant().minus(STALE_AFTER))) return Optional.empty();
        return Optional.of("The MaxMind file " + file.getFileName() + " was built on "
                + DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(built)
                + ". MaxMind's license asks for updates within 30 days of a new release. "
                + "The file is downloaded from GitHub Releases automatically.");
    }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        MaxMindDatabase current = reader.get();
        if (current == null) throw new LookupException("the MaxMind file is not loaded");
        InetAddress address = Addresses.parse(ip).orElseThrow(() -> new LookupException("not an IP address: " + ip));
        Map<?, ?> record;
        try {
            record = current.get(address);
        } catch (IOException | RuntimeException ex) {
            throw new LookupException("MaxMind lookup failed: " + Text.message(ex), ex);
        }
        if (record == null) throw new LookupException("the MaxMind file has no entry for " + ip);
        Map<?, ?> country = map(record, "country");
        if (country == null) country = map(record, "registered_country");
        return new IpInfo(ip, null, null, null, englishName(map(record, "city")), englishName(firstSubdivision(record)),
                englishName(country), text(country, "iso_code"), null, false, false, null, clock.instant());
    }

    private static Map<?, ?> firstSubdivision(Map<?, ?> record) {
        Object subdivisions = record.get("subdivisions");
        if (subdivisions instanceof List<?>) {
            List<?> list = (List<?>) subdivisions;
            if (!list.isEmpty() && list.get(0) instanceof Map<?, ?>) return (Map<?, ?>) list.get(0);
        }
        return null;
    }

    private static Map<?, ?> map(Map<?, ?> parent, String key) {
        return parent != null && parent.get(key) instanceof Map<?, ?> ? (Map<?, ?>) parent.get(key) : null;
    }

    private static String text(Map<?, ?> parent, String key) {
        return parent != null && parent.get(key) instanceof String ? (String) parent.get(key) : null;
    }

    private static String englishName(Map<?, ?> place) {
        return text(map(place, "names"), "en");
    }

    @Override public void close() {
        close(reader.getAndSet(null));
    }

    private static void close(MaxMindDatabase old) {
        if (old == null) return;
        try {
            old.close();
        } catch (IOException ignored) {
            // Nothing is left to release for an in-memory file.
        }
    }
}
