package io.github.origingate.core.net;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Immutable request used by OriginGate's Java 8 transport. */
public final class HttpRequest {
    final URI uri;
    final Duration timeout;
    final String method;
    final Map<String, String> headers;
    private HttpRequest(Builder builder) {
        uri = builder.uri; timeout = builder.timeout; method = builder.method;
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    }
    public static Builder newBuilder(URI uri) { return new Builder(uri); }
    public static final class Builder {
        private final URI uri;
        private Duration timeout = Duration.ofSeconds(30);
        private String method = "GET";
        private final Map<String,String> headers = new LinkedHashMap<>();
        private Builder(URI uri) {
            this.uri = Objects.requireNonNull(uri);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                throw new IllegalArgumentException("Only HTTP and HTTPS URLs are supported");
        }
        public Builder timeout(Duration value) {
            if (value.isNegative() || value.isZero()) throw new IllegalArgumentException("Timeout must be positive");
            timeout = value; return this;
        }
        public Builder header(String name, String value) { headers.put(name, value); return this; }
        public Builder GET() { method = "GET"; return this; }
        /** GET or HEAD: requests have no body. */
        public Builder method(String value) {
            if (!"GET".equals(value) && !"HEAD".equals(value)) throw new IllegalArgumentException("Unsupported HTTP method");
            method = value; return this;
        }
        public HttpRequest build() { return new HttpRequest(this); }
    }
}
