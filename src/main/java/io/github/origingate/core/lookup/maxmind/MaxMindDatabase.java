package io.github.origingate.core.lookup.maxmind;

import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

final class MaxMindDatabase implements AutoCloseable {
    private final Reader reader;
    private final Instant built;
    private final String type;

    MaxMindDatabase(Path file) throws IOException {
        if (Files.size(file) > 512L * 1024 * 1024)
            throw new IOException("MaxMind file is larger than 512 MiB");
        this.reader = new Reader(file.toFile());
        var meta = reader.getMetadata();
        this.built = meta.buildTime();
        this.type  = meta.databaseType();
    }

    Instant buildTime() { return built; }
    String databaseType() { return type; }

    @SuppressWarnings("unchecked")
    Map<String, Object> get(InetAddress address) throws IOException {
        return (Map<String, Object>) reader.get(address, Map.class);
    }

    @Override public void close() throws IOException { reader.close(); }
}