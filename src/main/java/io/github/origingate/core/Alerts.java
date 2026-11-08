package io.github.origingate.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff alerts about lookups, such as a provider that stopped answering. Callers may report a problem every time they
 * see it and a recovery on every success; {@link Once} sends each problem once per key and its recovery once.
 * Called from worker threads.
 */
public interface Alerts {
    /** A problem, such as "provider proxycheck" being paused. */
    void problem(String key, String message);

    /** The problem with this key is over. */
    void recovered(String key, String message);

    Alerts NONE = new Alerts() {
        @Override public void problem(String key, String message) { }
        @Override public void recovered(String key, String message) { }
    };

    /**
     * Sends a problem only when it is new for its key, and a recovery only for a key with a problem sent. The
     * controller keeps one across reloads, so a reload during an outage neither repeats the problem nor loses its recovery.
     */
    abstract class Once implements Alerts {
        private final Set<String> active = ConcurrentHashMap.newKeySet();

        @Override public final void problem(String key, String message) {
            if (active.add(key)) sendProblem(message);
        }

        @Override public final void recovered(String key, String message) {
            if (active.remove(key)) sendRecovered(message);
        }

        protected abstract void sendProblem(String message);

        protected abstract void sendRecovered(String message);
    }
}
