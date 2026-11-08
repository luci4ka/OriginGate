package io.github.origingate.core.net;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;

public final class HttpResponse<T> {
    private final int status;
    private final T body;
    private final Headers headers;
    HttpResponse(int status, T body, Map<String,List<String>> headers) {
        this.status = status; this.body = body; this.headers = new Headers(headers);
    }
    public int statusCode() { return status; }
    public T body() { return body; }
    public Headers headers() { return headers; }
    public static final class Headers {
        private final Map<String,List<String>> values;
        private Headers(Map<String,List<String>> values) { this.values = values; }
        public Optional<String> firstValue(String name) {
            for (Map.Entry<String,List<String>> item : values.entrySet()) {
                if (name.equalsIgnoreCase(item.getKey()) && !item.getValue().isEmpty()) return Optional.of(item.getValue().get(0));
            }
            return Optional.empty();
        }
    }
    public interface BodyHandler<T> { T read(InputStream input) throws IOException; }
    public static final class BodyHandlers {
        private BodyHandlers() { }
        public static BodyHandler<InputStream> ofInputStream() { return input -> input; }
        public static BodyHandler<Void> discarding() {
            return input -> { input.close(); return null; };
        }
        public static BodyHandler<String> ofString(Charset charset) {
            return input -> {
                try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192];
                    int read;
                    while ((read = stream.read(bytes)) != -1) {
                        if (output.size() + read > 1024 * 1024) throw new IOException("HTTP response exceeds 1 MiB");
                        output.write(bytes, 0, read);
                    }
                    return new String(output.toByteArray(), charset);
                }
            };
        }
    }
}
