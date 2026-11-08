package io.github.origingate.core.lookup;

import io.github.origingate.core.Alerts;
import io.github.origingate.core.Log;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses keys in turn. A refused key is skipped for {@link ProviderChain#REFUSED_PAUSE}, and the next key that is not
 * skipped is tried, until one answers. {@link KeyRejectedException} is thrown only when every key has been refused,
 * so the chain pauses the provider only then. With no keys, one request is sent without a key.
 * A refused key is reported once while other keys still work, and once more when it answers again.
 */
final class KeyRotation {
    interface Request {
        IpInfo send(String key) throws LookupException;
    }

    private final List<String> keys;
    private final String label;
    private final Clock clock;
    private final Log log;
    private final AtomicInteger next = new AtomicInteger();
    private final Map<Integer, Instant> refusedUntil = new ConcurrentHashMap<>();
    private volatile Alerts alerts = Alerts.NONE;

    KeyRotation(List<String> keys, String label, Clock clock, Log log) {
        this.keys = List.copyOf(keys);
        this.label = label;
        this.clock = clock;
        this.log = log;
    }

    void alertTo(Alerts alerts) {
        this.alerts = alerts;
    }

    /** No request after the first is started once {@code deadline} has passed. */
    IpInfo lookup(Request request, Instant deadline) throws LookupException {
        if (keys.isEmpty()) return request.send(null);
        int index = usable(Math.floorMod(next.getAndIncrement(), keys.size()));
        if (index < 0) throw new KeyRejectedException("all " + keys.size() + " API keys were refused in the last minute");
        KeyRejectedException last = null;
        // Keys refused in this lookup. They are reported only once another key answers; when none does, the whole
        // provider is paused and reported instead, so one event does not send two alerts.
        Map<Integer, String> refusedNow = new java.util.LinkedHashMap<>();
        // Each refusal skips that key, so this sends at most one request per key.
        for (int tries = 0; tries < keys.size() && index >= 0; tries++) {
            try {
                IpInfo info = request.send(keys.get(index));
                // Alerts.Once sends each of these only when it changes.
                alerts.recovered(keyName(index), label + " accepts API key " + (index + 1) + " again.");
                for (Map.Entry<Integer, String> refused : refusedNow.entrySet()) {
                    alerts.problem(keyName(refused.getKey()), label + " refused API key " + (refused.getKey() + 1) + " ("
                            + refused.getValue() + "). The other keys are used until it is accepted again.");
                }
                return info;
            } catch (KeyRejectedException ex) {
                refuse(index);
                last = ex;
                refusedNow.put(index, ex.getMessage());
                int following = usable((index + 1) % keys.size());
                if (following >= 0 && !clock.instant().isBefore(deadline)) {
                    log.warn(label + " refused API key " + (index + 1) + " (" + ex.getMessage() + "), no time left to try key "
                            + (following + 1), null);
                    // Other keys may still work, so this is a failed lookup, not a reason to pause the provider.
                    throw new LookupException(label + " refused API key " + (index + 1) + " and the lookup ran out of time: "
                            + ex.getMessage());
                }
                log.warn(label + " refused API key " + (index + 1) + " (" + ex.getMessage() + "), "
                        + (following < 0 ? "no other key is left" : "trying key " + (following + 1)), null);
                index = following;
            }
        }
        throw last;
    }

    private String keyName(int index) {
        return "key " + label + " " + (index + 1);
    }

    /** One line per key that is skipped right now. */
    List<String> status(Instant now) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            Instant until = refusedUntil.get(i);
            if (until != null && now.isBefore(until)) {
                lines.add("API key " + (i + 1) + ": refused, tried again in " + ProviderChain.seconds(now, until) + " s");
            }
        }
        return lines;
    }

    /** The first key from {@code start} on that is not paused, or -1 when all are. */
    private int usable(int start) {
        Instant now = clock.instant();
        for (int i = 0; i < keys.size(); i++) {
            int index = (start + i) % keys.size();
            Instant until = refusedUntil.get(index);
            if (until == null || !now.isBefore(until)) return index;
        }
        return -1;
    }

    private void refuse(int index) {
        refusedUntil.put(index, clock.instant().plus(ProviderChain.REFUSED_PAUSE));
    }
}
