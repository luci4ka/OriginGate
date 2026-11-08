package ru.terracraft.origingate.core.lookup.maxmind;

import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.Text;
import ru.terracraft.origingate.core.net.HttpRequest;
import ru.terracraft.origingate.core.net.HttpResponse;
import ru.terracraft.origingate.core.net.HttpTransport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

/**
 * Downloads the latest GeoLite2 file from GitHub Releases, without a MaxMind account.
 * Uses {@code /releases/latest/download/<name>}, which GitHub redirects to the newest asset.
 * Format reference: https://github.com/P3TERX/GeoLite.mmdb
 * The downloaded file is opened once to check it is the configured edition and to read its build date.
 * A download is skipped when the file in use already has that build date.
 */
public final class MaxMindUpdater {
    /** GitHub Releases of P3TERX/GeoLite.mmdb. {@code /latest/download/} always redirects to the newest asset. */
    public static final URI BASE_URL = URI.create("https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/");
    public static final Duration CHECK_EVERY = Duration.ofHours(24);
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    public enum Outcome { DOWNLOADED, UP_TO_DATE }

    private final HttpTransport http;
    private final String edition;
    private final MaxMindProvider provider;
    private final Path file;
    private final Log log;
    private final String userAgent;

    public MaxMindUpdater(HttpTransport http, String edition, MaxMindProvider provider, Path file, Log log, String userAgent) {
        this.http = http;
        this.edition = edition;
        this.provider = provider;
        this.file = file;
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

    /** Checks once and downloads unless the file in use already has the build date from GitHub. Blocks. */
    public Outcome update() throws IOException {
        URI url = URI.create(BASE_URL + edition + ".mmdb");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".download");
        Instant built;
        try {
            HttpResponse<InputStream> download = download(url);
            try {
                int code = download.statusCode();
                if (code != 200) {
                    discard(download);
                    throw new IOException("GitHub answered HTTP " + code);
                }
                try (InputStream body = download.body()) {
                    copy(body, temp);
                }
            } catch (IOException ex) {
                discard(download);
                throw ex;
            }
            built = check(temp);
            if (isCurrent(built)) {
                log.debug("The MaxMind file for " + edition + " is already built on " + built + ", not downloading again");
                return Outcome.UP_TO_DATE;
            }
            move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
        provider.reload();
        log.info("Downloaded MaxMind " + edition + " built on " + built);
        return Outcome.DOWNLOADED;
    }

    /** Sends the request and follows one redirect, as GitHub always redirects {@code /latest/download/}. */
    private HttpResponse<InputStream> download(URI url) throws IOException {
        HttpResponse<InputStream> response = send(url);
        int code = response.statusCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
            discard(response);
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("GitHub sent a redirect without a Location"));
            response = send(url.resolve(location));
        }
        return response;
    }

    private HttpResponse<InputStream> send(URI uri) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .GET();
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("The MaxMind download was interrupted", ex);
        }
    }

    /** True when the file in use is the configured edition and was built on {@code built}. */
    private boolean isCurrent(Instant built) {
        if (!provider.databaseType().map(edition::equals).orElse(false)) return false;
        return provider.buildTime().map(built::equals).orElse(false);
    }

    /** Copies the body to {@code target}, refusing files over {@link #MAX_FILE_BYTES}. */
    private static void copy(InputStream input, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("The downloaded MaxMind file is larger than " + MAX_FILE_BYTES + " bytes");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static void discard(HttpResponse<?> response) throws IOException {
        if (response.body() instanceof InputStream) ((InputStream) response.body()).close();
    }

    /** Opens the new file once, checks it is the configured edition, and returns its build date. */
    private Instant check(Path candidate) throws IOException {
        try (MaxMindDatabase reader = new MaxMindDatabase(candidate)) {
            String type = reader.databaseType();
            if (!edition.equals(type)) throw new IOException("GitHub sent a " + type + " file instead of " + edition);
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