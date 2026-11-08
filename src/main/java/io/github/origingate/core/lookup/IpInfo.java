package io.github.origingate.core.lookup;

import java.time.Instant;
import java.util.Objects;

/** What is known about one IP address. Text fields are null when unknown. */
public final class IpInfo {
    private final String ip;
    private final String provider;
    private final String organisation;
    private final String operatorName;
    private final String city;
    private final String region;
    private final String country;
    private final String countryCode;
    private final String asn;
    private final boolean vpn;
    private final boolean proxy;
    private final String type;
    private final Instant checkedAt;

    public IpInfo(String ip, String provider, String organisation, String operatorName, String city, String region, String country, String countryCode, String asn, boolean vpn, boolean proxy, String type, Instant checkedAt) {
        Objects.requireNonNull(ip, "ip");
        Objects.requireNonNull(checkedAt, "checkedAt");
        provider = clean(provider);
        organisation = clean(organisation);
        operatorName = clean(operatorName);
        city = clean(city);
        region = clean(region);
        country = clean(country);
        countryCode = clean(countryCode);
        if (countryCode != null) countryCode = countryCode.toUpperCase(java.util.Locale.ROOT);
        asn = clean(asn);
        type = clean(type);
        this.ip = ip;
        this.provider = provider;
        this.organisation = organisation;
        this.operatorName = operatorName;
        this.city = city;
        this.region = region;
        this.country = country;
        this.countryCode = countryCode;
        this.asn = asn;
        this.vpn = vpn;
        this.proxy = proxy;
        this.type = type;
        this.checkedAt = checkedAt;
    }

    public String ip() { return ip; }
    public String provider() { return provider; }
    public String organisation() { return organisation; }
    public String operatorName() { return operatorName; }
    public String city() { return city; }
    public String region() { return region; }
    public String country() { return country; }
    public String countryCode() { return countryCode; }
    public String asn() { return asn; }
    public boolean vpn() { return vpn; }
    public boolean proxy() { return proxy; }
    public String type() { return type; }
    public Instant checkedAt() { return checkedAt; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof IpInfo)) return false;
        IpInfo that = (IpInfo) other;
        return java.util.Objects.equals(ip, that.ip)
                && java.util.Objects.equals(provider, that.provider)
                && java.util.Objects.equals(organisation, that.organisation)
                && java.util.Objects.equals(operatorName, that.operatorName)
                && java.util.Objects.equals(city, that.city)
                && java.util.Objects.equals(region, that.region)
                && java.util.Objects.equals(country, that.country)
                && java.util.Objects.equals(countryCode, that.countryCode)
                && java.util.Objects.equals(asn, that.asn)
                && java.util.Objects.equals(vpn, that.vpn)
                && java.util.Objects.equals(proxy, that.proxy)
                && java.util.Objects.equals(type, that.type)
                && java.util.Objects.equals(checkedAt, that.checkedAt);
    }
    @Override public int hashCode() { return java.util.Objects.hash(ip, provider, organisation, operatorName, city, region, country, countryCode, asn, vpn, proxy, type, checkedAt); }
    @Override public String toString() { return "IpInfo[" + "ip=" + ip + ", " + "provider=" + provider + ", " + "organisation=" + organisation + ", " + "operatorName=" + operatorName + ", " + "city=" + city + ", " + "region=" + region + ", " + "country=" + country + ", " + "countryCode=" + countryCode + ", " + "asn=" + asn + ", " + "vpn=" + vpn + ", " + "proxy=" + proxy + ", " + "type=" + type + ", " + "checkedAt=" + checkedAt + "]"; }



    /** The network type that marks hosting and datacenter networks: proxycheck.io's own, and set for ip-api's hosting flag. */
    public static final String HOSTING = "Hosting";

    /** True when the VPN answer says the network is a hosting or datacenter network. */
    public boolean hosting() {
        return HOSTING.equalsIgnoreCase(type);
    }

    /** The same data for another address, for an IPv6 address answered from its /64 network. */
    public IpInfo withIp(String other) {
        if (ip.equals(other)) return this;
        return new IpInfo(other, provider, organisation, operatorName, city, region, country, countryCode, asn, vpn, proxy,
                type, checkedAt);
    }

    /** The VPN operator name when it looks meaningful, otherwise the network organisation. */
    public String displayOrganisation() {
        return operatorName != null && operatorName.length() > 3 ? operatorName : organisation;
    }

    /** Trims, removes control characters, limits length, and turns blanks or "-" into null. */
    static String clean(String value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(Math.min(value.length(), 255));
        for (int i = 0; i < value.length() && result.length() < 255; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) result.append(c);
        }
        String text = result.toString().trim();
        return text.isEmpty() || text.equals("-") ? null : text;
    }
}
