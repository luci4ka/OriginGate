package io.github.origingate.core.lookup;

import com.google.gson.JsonObject;
import io.github.origingate.core.Log;

import java.net.URI;
import io.github.origingate.core.net.HttpTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * IPHub v2 API. Format reference: https://iphub.info/api
 * Keys go in the X-Key header and are used in turn. {@code block: 1} (non-residential: hosting, proxy, VPN)
 * counts as a VPN. {@code block: 2} is ignored, since IPHub says it may flag innocent users.
 */
public final class IpHubProvider implements LookupProvider {
    public static final URI BASE_URL = URI.create("https://v2.api.iphub.info/ip/");
    private static final String LABEL = "IPHub";
    private final HttpLookup http;
    private final String baseUrl;
    private final KeyRotation keys;
    private final Clock clock;

    public IpHubProvider(HttpTransport http, URI baseUrl, List<String> keys, Duration requestTimeout, Clock clock, Log log,
                         String userAgent) {
        this.http = new HttpLookup(http, requestTimeout, userAgent, LABEL);
        this.baseUrl = HttpLookup.withSlash(baseUrl);
        this.keys = new KeyRotation(keys, LABEL, clock, log);
        this.clock = clock;
    }

    @Override public String name() { return "iphub"; }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        return keys.lookup(key -> request(ip, key), deadline);
    }

    @Override public void alertTo(io.github.origingate.core.Alerts alerts) { keys.alertTo(alerts); }

    @Override public List<String> keyStatus(Instant now) { return keys.status(now); }

    private IpInfo request(String ip, String key) throws LookupException {
        HttpLookup.Response response = http.get(URI.create(baseUrl + ip),
                key == null ? Map.of() : Map.of("X-Key", key));
        int code = response.status();
        if (code == 401 || code == 403 || code == 429) throw new KeyRejectedException("HTTP " + code);
        if (code != 200) throw new LookupException(LABEL + " answered HTTP " + code);
        return parse(ip, response.body(), clock.instant());
    }

    /** Parses a v2 response for {@code ip}. Public for fixture tests. */
    public static IpInfo parse(String ip, String body, Instant checkedAt) throws LookupException {
        JsonObject root = Json.parse(LABEL, body);
        String block = Json.text(root, "block");
        if (block == null) throw new LookupException(LABEL + " response has no block value");
        String asn = Json.text(root, "asn");
        return new IpInfo(ip, Json.text(root, "isp"), null, null, null, null, Json.text(root, "countryName"),
                Json.text(root, "countryCode"), asn == null || asn.equals("0") ? null : "AS" + asn,
                "1".equals(block), false, null, checkedAt);
    }
}
