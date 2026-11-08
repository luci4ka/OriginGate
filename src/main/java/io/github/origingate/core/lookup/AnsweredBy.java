package io.github.origingate.core.lookup;

/** Which providers answered a fresh lookup. {@code vpn} is null when {@code vpn-from} is empty. */
public final class AnsweredBy {
    private final String country;
    private final String vpn;

    public AnsweredBy(String country, String vpn) {
        this.country = country;
        this.vpn = vpn;
    }

    public String country() { return country; }
    public String vpn() { return vpn; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AnsweredBy)) return false;
        AnsweredBy that = (AnsweredBy) other;
        return java.util.Objects.equals(country, that.country)
                && java.util.Objects.equals(vpn, that.vpn);
    }
    @Override public int hashCode() { return java.util.Objects.hash(country, vpn); }
    @Override public String toString() { return "AnsweredBy[" + "country=" + country + ", " + "vpn=" + vpn + "]"; }

    /** For log lines: "maxmind+proxycheck", or one name when one provider answered both jobs. */
    public String joined() {
        return vpn == null || vpn.equals(country) ? country : country + "+" + vpn;
    }

    /** For the check command: "maxmind (country), proxycheck (vpn)". */
    public String described() {
        if (vpn == null) return country + " (country)";
        if (vpn.equals(country)) return country + " (country, vpn)";
        return country + " (country), " + vpn + " (vpn)";
    }
}
