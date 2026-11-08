package io.github.origingate.core.lookup;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded, least-recently-used cache. Entries expire {@code maxAge} after they were checked. */
public final class MemoryCache {
    private final Duration maxAge;
    private final Clock clock;
    private final Map<String, IpInfo> entries;

    public MemoryCache(int maxEntries, Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
        this.entries = new LinkedHashMap<String, IpInfo>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, IpInfo> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized Optional<IpInfo> get(String ip) {
        IpInfo info = entries.get(ip);
        if (info == null) return Optional.empty();
        if (!info.checkedAt().plus(maxAge).isAfter(clock.instant())) {
            entries.remove(ip);
            return Optional.empty();
        }
        return Optional.of(info);
    }

    public synchronized void put(IpInfo info) {
        put(info.ip(), info);
    }

    /** Stores {@code info} under {@code key}, which is the address or, for IPv6, its /64 network. */
    public synchronized void put(String key, IpInfo info) {
        entries.put(key, info);
    }

    public synchronized boolean remove(String ip) {
        return entries.remove(ip) != null;
    }

    public synchronized int clear() {
        int size = entries.size();
        entries.clear();
        return size;
    }

    public synchronized int size() {
        return entries.size();
    }
}
