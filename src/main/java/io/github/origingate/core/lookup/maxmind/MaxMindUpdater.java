package io.github.origingate.core.lookup.maxmind;


import io.github.origingate.core.Log;
import io.github.origingate.core.Text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import io.github.origingate.core.net.HttpTransport;
import io.github.origingate.core.net.HttpRequest;
import io.github.origingate.core.net.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;

/**
 * Downloads the MaxMind file with the owner's account ID and license key, and replaces it when MaxMind has a
 * newer release. Format reference: https://dev.maxmind.com/geoip/updating-databases/
 * A HEAD request reads Last-Modified (MaxMind says HEAD does not count toward the download limit). After a download,
 * that release date and the file's build date go into {@code <file>.release}; a download is skipped only when the file
 * in use has that build date, so a file placed or restored by hand is replaced. MaxMind redirects to a storage host;
 * the redirect is followed by hand so the credentials are sent only to MaxMind.
 */
public final class MaxMindUpdater {
    public static final URI DOWNLOAD_URL = URI.create("https://download.maxmind.com/geoip/databases/");
    public static final Duration CHECK_EVERY = Duration.ofHours(24);
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    public enum Outcome { DOWNLOADED, UP_TO_DATE }

    private final HttpTransport http;
    private final URI url;
    private final String edition;
    private final String authorization;
    private final MaxMindProvider provider;
    private final Path file;
    private final Path releaseFile;
    private final Log log;
    private final String userAgent;

    public MaxMindUpdater(HttpTransport http, URI baseUrl, String edition, int accountId, String licenseKey,
                          MaxMindProvider provider, Path file, Log log, String userAgent) {
        this.http = http;
        String base = baseUrl.toString();
        this.url = URI.create((base.endsWith("/") ? base : base + "/") + edition + "/download?suffix=tar.gz");
        this.edition = edition;
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((accountId + ":" + licenseKey).getBytes(StandardCharsets.UTF_8));
        this.provider = provider;
        this.file = file;
        this.releaseFile = file.resolveSibling(file.getFileName() + ".release");
        this.log = log;
        this.userAgent = userAgent;
    }

    /** For the scheduler: checks once and logs a failure instead of throwing. */
    public void runSafely() {
        try {
            update();
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not update the MaxMind file, the current one stays in use: " + Text.message(ex), null);
        }
    }

    /** Checks once and downloads unless the file in use is the one downloaded for the latest release. Blocks. */
    public Outcome update() throws IOException {
        Instant released = lastModified(send("HEAD", HttpResponse.BodyHandlers.discarding()));
        if (isCurrent(released)) return Outcome.UP_TO_DATE;
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".download");
        Instant built;
        try {
            HttpResponse<InputStream> download = send("GET", HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = download.body()) {
                TarGz.extract(body, ".mmdb", temp, MAX_FILE_BYTES);
            }
            built = check(temp);
            move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
        Files.writeString(releaseFile, "# Written by OriginGate's MaxMind updater. Delete it to download the file again.\n"
                + "released=" + released + "\nbuilt=" + built + "\n", StandardCharsets.UTF_8);
        provider.reload();
        log.info("Downloaded MaxMind " + edition + " released " + released);
        return Outcome.DOWNLOADED;
    }

    /**
     * True when the file in use is the configured edition, and {@code <file>.release} records {@code released} or a
     * later release with the same build date as that file. Anything else (another edition, no record, a file placed or
     * restored by hand, a file that did not load) is not trusted.
     */
    private boolean isCurrent(Instant released) {
        Optional<Instant> inUse = provider.buildTime();
        if (!inUse.isPresent() || !Files.isRegularFile(releaseFile)) return false;
        if (!provider.databaseType().map(edition::equals).orElse(false)) return false;
        try (BufferedReader text = Files.newBufferedReader(releaseFile, StandardCharsets.UTF_8)) {
            Properties record = new Properties();
            record.load(text);
            return Instant.parse(record.getProperty("built")).equals(inUse.get())
                    && !Instant.parse(record.getProperty("released")).isBefore(released);
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** Sends to MaxMind with credentials, then follows one redirect without them. */
    private <T> HttpResponse<T> send(String method, HttpResponse.BodyHandler<T> handler) throws IOException {
        HttpResponse<T> response = request(url, method, handler, true);
        int code = response.statusCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            discard(response);
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("MaxMind sent a redirect without a Location"));
            response = request(url.resolve(location), method, handler, false);
            code = response.statusCode();
        }
        if (code == 200) return response;
        discard(response);
        if (code == 401) throw new IOException("MaxMind refused the account ID or license key (HTTP 401)");
        if (code == 429) throw new IOException("MaxMind limits downloads right now (HTTP 429)");
        throw new IOException("MaxMind answered HTTP " + code);
    }

    private <T> HttpResponse<T> request(URI uri, String method, HttpResponse.BodyHandler<T> handler, boolean withCredentials)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .method(method);
        if (withCredentials) request.header("Authorization", authorization);
        try {
            return http.send(request.build(), handler);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("The MaxMind request was interrupted", ex);
        }
    }

    private static void discard(HttpResponse<?> response) throws IOException {
        if (response.body() instanceof InputStream) ((InputStream) response.body()).close();
    }

    private static Instant lastModified(HttpResponse<?> response) throws IOException {
        String value = response.headers().firstValue("Last-Modified")
                .orElseThrow(() -> new IOException("MaxMind sent no Last-Modified date"));
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IOException("MaxMind sent an unreadable Last-Modified date: " + value, ex);
        }
    }

    /** Opens the new file once, checks it is the configured edition, and returns its build date. */
    private Instant check(Path candidate) throws IOException {
        try (MaxMindDatabase reader = new MaxMindDatabase(candidate)) {
            String type = reader.databaseType();
            if (!edition.equals(type)) throw new IOException("MaxMind sent a " + type + " file instead of " + edition);
            return reader.buildTime();
        } catch (RuntimeException ex) {
            throw new IOException("The downloaded MaxMind file cannot be read: " + Text.message(ex), ex);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
