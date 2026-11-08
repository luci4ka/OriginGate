package io.github.origingate.core.lookup;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/** Allows at most {@code perMinute} provider lookups in any 60 seconds. 0 means no limit. */
final class LookupBudget {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final int perMinute;
    /** Start times of the lookups in the last minute, oldest first. Guarded by this. */
    private final ArrayDeque<Instant> recent = new ArrayDeque<>();

    LookupBudget(int perMinute) {
        this.perMinute = perMinute;
    }

    int perMinute() { return perMinute; }

    /** Takes one lookup from the budget, or returns false when the last minute already used all of it. */
    synchronized boolean take(Instant now) {
        if (perMinute == 0) return true;
        drop(now);
        if (recent.size() >= perMinute) return false;
        recent.addLast(now);
        return true;
    }

    /** Lookups started in the last minute. */
    synchronized int used(Instant now) {
        drop(now);
        return recent.size();
    }

    private void drop(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) recent.removeFirst();
    }
}
