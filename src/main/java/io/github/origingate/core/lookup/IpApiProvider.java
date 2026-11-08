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

public final class IpApiProvider implements LookupProvider {
    public static final URI FREE_URL = URI.create("http://ip-api.com/json/");
    public static final URI PRO_URL = URI.create("https://pro.ip-api.com/json/");
    static final String FIELDS = "status,message,country,countryCode,regionName,city,isp,org,as,proxy,hosting";
    private static final String LABEL = "ip-api";
    private final HttpLookup http;
    private final String baseUrl;
    private final String key;
    private final Clock clock;

    public IpApiProvider(HttpTransport http, URI baseUrl, String key, Duration requestTimeout, Clock clock, String userAgent) {
        this.http = new HttpLookup(http, requestTimeout, userAgent, LABEL);
        this.baseUrl = HttpLookup.withSlash(baseUrl);
        this.key = key;
        this.clock = clock;
    }

    @Override public String name() { return "ip-api"; }

    @Override public IpInfo lookup(String ip, Instant deadline) throws LookupException {
        String query = "?fields=" + FIELDS + (key.isEmpty() ? "" : "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        HttpLookup.Response response = http.get(URI.create(baseUrl + ip + query), Map.of());
        int code = response.status();
        if (code == 403 || code == 429) throw new KeyRejectedException("HTTP " + code + Json.message(response.body()));
        if (code != 200) throw new LookupException(LABEL + " answered HTTP " + code + Json.message(response.body()));
        return parse(ip, response.body(), clock.instant());
    }

    public static IpInfo parse(String ip, String body, Instant checkedAt) throws LookupException {
        JsonObject root = Json.parse(LABEL, body);
        String status = Json.text(root, "status");
        if (!"success".equals(status)) throw new LookupException(LABEL + " answered status " + status + Json.message(root));
        String as = Json.text(root, "as");
        String asn = as != null && as.startsWith("AS") ? as.split(" ", 2)[0] : null;
        return new IpInfo(ip, Json.text(root, "isp"), Json.text(root, "org"), null, Json.text(root, "city"),
                Json.text(root, "regionName"), Json.text(root, "country"), Json.text(root, "countryCode"), asn,
                Json.bool(root, "proxy"), false, Json.bool(root, "hosting") ? IpInfo.HOSTING : null, checkedAt);
    }
}