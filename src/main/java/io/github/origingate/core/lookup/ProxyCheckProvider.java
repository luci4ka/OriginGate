package io.github.origingate.core.lookup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.origingate.core.Log;

import java.net.URI;
import java.net.URLEncoder;
import io.github.origingate.core.net.HttpTransport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * proxycheck.io v3 API. Format reference: https://proxycheck.io/api/
 * Keys are used in turn. A rejected or rate-limited key is followed by one try with the next key.
 */
public final class ProxyCheckProvider implements LookupProvider {
    private static final String LABEL = "proxycheck.io";
    private final HttpLookup http;
    private final String baseUrl;
    private final KeyRotation keys;
    private final Clock clock;

    public ProxyCheckProvider(HttpTransport http, URI baseUrl, List<String> keys, Duration requestTimeout, Clock clock,
                              Log log, String userAgent) {
        this.http = new HttpLookup(http, requestTimeout, userAgent, LABEL);
        this.baseUrl = HttpLookup.withSlash(baseUrl);
        this.keys = new KeyRotation(keys, LABEL, clock, log);
        this.clock = clock;
    }

    @Override public String name() { return "proxycheck"; }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        return keys.lookup(key -> request(ip, key), deadline);
    }

    @Override public void alertTo(io.github.origingate.core.Alerts alerts) { keys.alertTo(alerts); }

    @Override public List<String> keyStatus(Instant now) { return keys.status(now); }

    private IpInfo request(String ip, String key) throws LookupException {
        URI uri = URI.create(baseUrl + ip + (key == null ? "" : "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)));
        HttpLookup.Response response = http.get(uri, Map.of());
        int code = response.status();
        if (code == 401 || code == 403 || code == 429) throw new KeyRejectedException("HTTP " + code + Json.message(response.body()));
        if (code != 200) throw new LookupException(LABEL + " answered HTTP " + code + Json.message(response.body()));
        return parse(ip, response.body(), clock.instant());
    }

    /** Parses a v3 response for {@code ip}. Public for fixture tests. */
    public static IpInfo parse(String ip, String body, Instant checkedAt) throws LookupException {
        JsonObject root = Json.parse(LABEL, body);
        String status = Json.text(root, "status");
        if ("denied".equals(status)) throw new KeyRejectedException("status denied" + Json.message(root));
        if (!"ok".equals(status) && !"warning".equals(status)) {
            throw new LookupException(LABEL + " answered status " + status + Json.message(root));
        }
        JsonObject entry = entry(root, ip);
        if (entry == null) throw new LookupException(LABEL + " response has no data for " + ip);
        JsonObject network = Json.object(entry, "network");
        JsonObject location = Json.object(entry, "location");
        JsonObject detections = Json.object(entry, "detections");
        JsonObject operator = Json.object(entry, "operator");
        return new IpInfo(ip, Json.text(network, "provider"), Json.text(network, "organisation"), Json.text(operator, "name"),
                Json.text(location, "city_name"), Json.text(location, "region_name"), Json.text(location, "country_name"),
                Json.text(location, "country_code"), Json.text(network, "asn"), Json.bool(detections, "vpn"),
                Json.bool(detections, "proxy"), Json.text(network, "type"), checkedAt);
    }

    /** The object for this IP. The API may print IPv6 addresses differently, so fall back to the only entry. */
    private static JsonObject entry(JsonObject root, String ip) {
        JsonObject exact = Json.object(root, ip);
        if (exact != null) return exact;
        JsonObject found = null;
        for (Map.Entry<String, JsonElement> member : root.entrySet()) {
            if (member.getValue().isJsonObject() && member.getValue().getAsJsonObject().has("detections")) {
                if (found != null) return null;
                found = member.getValue().getAsJsonObject();
            }
        }
        return found;
    }
}
