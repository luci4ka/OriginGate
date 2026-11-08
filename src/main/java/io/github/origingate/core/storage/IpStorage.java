package io.github.origingate.core.storage;

import io.github.origingate.core.lookup.IpInfo;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** Saved lookups. Every method blocks, so call them from a worker thread. */
public interface IpStorage extends AutoCloseable {
    /** A row checked at or after {@code notBefore}. */
    Optional<IpInfo> find(String ip, Instant notBefore) throws SQLException;

    /** Inserts or replaces the row for this IP. */
    void save(IpInfo info) throws SQLException;

    /** Deletes rows checked before {@code cutoff}. */
    int deleteOlderThan(Instant cutoff) throws SQLException;

    int delete(String ip) throws SQLException;

    int deleteAll() throws SQLException;

    @Override default void close() { }

    /** Saves nothing, for a system where SQLite cannot run: lookups are then kept in memory only. */
    IpStorage NONE = new IpStorage() {
        @Override public Optional<IpInfo> find(String ip, Instant notBefore) { return Optional.empty(); }
        @Override public void save(IpInfo info) { }
        @Override public int deleteOlderThan(Instant cutoff) { return 0; }
        @Override public int delete(String ip) { return 0; }
        @Override public int deleteAll() { return 0; }
    };
}
