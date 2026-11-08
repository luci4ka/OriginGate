package io.github.origingate.core.lookup;

import io.github.origingate.core.Alerts;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** Looks up one IP address from an external service or file. Called from a worker thread. */
public interface LookupProvider {
    /** The name used in {@code country-from}, {@code vpn-from}, and logs, for example "proxycheck". */
    String name();

    /** {@code deadline} is when the lookup gives up; a provider that retries with other keys starts no request after it. */
    IpInfo lookup(String ip, Instant deadline) throws LookupException;

    /** Where a provider with several API keys reports a refused key and its recovery. */
    default void alertTo(Alerts alerts) { }

    /** One line per API key that is paused right now, for {@code origingate status}. */
    default List<String> keyStatus(Instant now) { return Collections.emptyList(); }
}
