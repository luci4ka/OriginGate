package io.github.origingate.core.lookup;

import java.io.IOException;
import java.net.URI;
import io.github.origingate.core.net.HttpTransport;
import io.github.origingate.core.net.HttpRequest;
import io.github.origingate.core.net.HttpResponse;
import io.github.origingate.core.net.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Sends one GET request for a web provider. Network errors become {@link LookupException}s named after the provider. */
final class HttpLookup {
    static final int MAX_BODY_CHARS = 256 * 1024;

    static final class Response {
        private final int status;
        private final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Response)) return false;
            Response that = (Response) other;
            return java.util.Objects.equals(status, that.status)
                && java.util.Objects.equals(body, that.body);
        }
        @Override public int hashCode() { return java.util.Objects.hash(status, body); }
        @Override public String toString() { return "Response[" + "status=" + status + ", " + "body=" + body + "]"; }
 }

    private final HttpTransport http;
    private final Duration timeout;
    private final String userAgent;
    private final String label;

    HttpLookup(HttpTransport http, Duration timeout, String userAgent, String label) {
        this.http = http;
        this.timeout = timeout;
        this.userAgent = userAgent;
        this.label = label;
    }

    /** The URL as text, ending with a slash so an IP can be appended. */
    static String withSlash(URI url) {
        String text = url.toString();
        return text.endsWith("/") ? text : text + "/";
    }

    Response get(URI uri, Map<String, String> headers) throws LookupException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .GET();
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (HttpTimeoutException ex) {
            throw new LookupException(label + " did not answer within " + timeout.toMillis() + " ms", ex);
        } catch (IOException ex) {
            throw new LookupException(label + " request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LookupException(label + " request was interrupted", ex);
        }
    }
}
