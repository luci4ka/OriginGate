package io.github.origingate.core.lookup;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import io.github.origingate.core.net.HttpTransport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * IPinfo Lite API. Format reference: https://ipinfo.io/developers/lite-api
 * Country and ASN only; the free plan has no VPN check, so this provider is for {@code country-from} only.
 */
public final class IpInfoProvider implements LookupProvider {
    public static final URI BASE_URL = URI.create("https://api.ipinfo.io/lite/");
    private static final String LABEL = "IPinfo";
    private final HttpLookup http;
    private final String baseUrl;
    private final String token;
    private final Clock clock;

    public IpInfoProvider(HttpTransport http, URI baseUrl, String token, Duration requestTimeout, Clock clock, String userAgent) {
        this.http = new HttpLookup(http, requestTimeout, userAgent, LABEL);
        this.baseUrl = HttpLookup.withSlash(baseUrl);
        this.token = token;
        this.clock = clock;
    }

    @Override public String name() { return "ipinfo"; }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        URI uri = URI.create(baseUrl + ip + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        HttpLookup.Response response = http.get(uri, Map.of());
        int code = response.status();
        if (code == 401 || code == 403 || code == 429) throw new KeyRejectedException("HTTP " + code);
        if (code != 200) throw new LookupException(LABEL + " answered HTTP " + code);
        return parse(ip, response.body(), clock.instant());
    }

    /** Parses a Lite response for {@code ip}. Public for fixture tests. */
    public static IpInfo parse(String ip, String body, Instant checkedAt) throws LookupException {
        JsonObject root = Json.parse(LABEL, body);
        return new IpInfo(ip, Json.text(root, "as_name"), null, null, null, null, Json.text(root, "country"),
                Json.text(root, "country_code"), Json.text(root, "asn"), false, false, null, checkedAt);
    }
}
