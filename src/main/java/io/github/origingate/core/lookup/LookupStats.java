package io.github.origingate.core.lookup;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory counters for {@code origingate status}. Kept across reloads and reset when the plugin starts. */
public final class LookupStats {
    private final Clock clock;
    private final ZoneId zone;
    private final Instant since;
    private final AtomicLong memory = new AtomicLong();
    private final AtomicLong storage = new AtomicLong();
    private final AtomicLong provider = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong overBudget = new AtomicLong();
    private final AtomicLong skippedByBudget = new AtomicLong();
    private volatile Instant lastOverBudget;
    /** Requests per provider name on {@link #day}. Guarded by this. */
    private final Map<String, Long> requestsToday = new HashMap<>();
    private LocalDate day;

    public LookupStats(Clock clock, ZoneId zone) {
        this.clock = clock;
        this.zone = zone;
        this.since = clock.instant();
    }

    public LookupStats() { this(Clock.systemDefaultZone(), ZoneId.systemDefault()); }

    void answered(LookupService.Source source) {
        switch (source) {
            case MEMORY: memory.incrementAndGet(); break;
            case STORAGE: storage.incrementAndGet(); break;
            default: provider.incrementAndGet();
        }
    }

    void countFailed() { failed.incrementAndGet(); }

    void countDropped() { dropped.incrementAndGet(); }

    /** One login over the budget. Kept here rather than per runtime, so a reload during a flood does not end it. */
    void countOverBudget(Instant now) {
        overBudget.incrementAndGet();
        skippedByBudget.incrementAndGet();
        lastOverBudget = now;
    }

    /** When a login was last over the budget, or null. */
    Instant lastOverBudget() { return lastOverBudget; }

    /** Logins over the budget since the last call. */
    long takeSkippedByBudget() { return skippedByBudget.getAndSet(0); }

    /** One request sent to {@code name}, counted for today in the server's time zone. */
    synchronized void requested(String name) {
        roll();
        requestsToday.merge(name, 1L, Long::sum);
    }

    public synchronized long requestsToday(String name) {
        roll();
        return requestsToday.getOrDefault(name, 0L);
    }

    private void roll() {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        if (!today.equals(day)) {
            day = today;
            requestsToday.clear();
        }
    }

    public Instant since() { return since; }
    public long memory() { return memory.get(); }
    public long storage() { return storage.get(); }
    public long provider() { return provider.get(); }
    public long failed() { return failed.get(); }
    public long dropped() { return dropped.get(); }
    public long overBudget() { return overBudget.get(); }

    /** Memory and storage answers out of all answered and failed lookups, from 0 to 100, or -1 before the first. */
    public int hitRatePercent() {
        long hits = memory() + storage();
        long total = hits + provider() + failed();
        return total == 0 ? -1 : (int) Math.round(hits * 100.0 / total);
    }
}
