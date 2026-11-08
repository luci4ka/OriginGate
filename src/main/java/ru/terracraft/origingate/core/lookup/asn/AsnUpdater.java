package ru.terracraft.origingate.core.lookup.asn;

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

/** Downloads GeoLite2-ASN.mmdb from GitHub Releases, same source as the Country file. */
public final class AsnUpdater {
    public static final URI BASE_URL = URI.create("https://github.com/P3TERX/GeoLite.mmdb/releases/latest/download/");
    public static final Duration CHECK_EVERY = Duration.ofHours(24);
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    public enum Outcome { DOWNLOADED, UP_TO_DATE }

    private final HttpTransport http;
    private final AsnProvider provider;
    private final Path file;
    private final Log log;
    private final String userAgent;

    public AsnUpdater(HttpTransport http, AsnProvider provider, Path file, Log log, String userAgent) {
        this.http = http;
        this.provider = provider;
        this.file = file;
        this.log = log;
        this.userAgent = userAgent;
    }

    public void runSafely() {
        try {
            update();
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not update the ASN file, the current one stays in use: " + Text.message(ex), null);
        }
    }

    public Outcome update() throws IOException {
        URI url = URI.create(BASE_URL + "GeoLite2-ASN.mmdb");
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".download");
        try {
            HttpResponse<InputStream> download = download(url);
            int code = download.statusCode();
            if (code != 200) {
                discard(download);
                throw new IOException("GitHub answered HTTP " + code);
            }
            try (InputStream body = download.body()) {
                copy(body, temp);
            }
            move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
        provider.reload();
        log.info("Downloaded ASN database");
        return Outcome.DOWNLOADED;
    }

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
            throw new IOException("The ASN download was interrupted", ex);
        }
    }

    private static void copy(InputStream input, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) throw new IOException("ASN file is larger than " + MAX_FILE_BYTES + " bytes");
                output.write(buffer, 0, read);
            }
        }
    }

    private static void discard(HttpResponse<?> response) throws IOException {
        if (response.body() instanceof InputStream) ((InputStream) response.body()).close();
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}