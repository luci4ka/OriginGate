package io.github.origingate.core.rules;

import io.github.origingate.core.Log;
import io.github.origingate.core.Text;
import io.github.origingate.core.config.OriginGateConfig;
import io.github.origingate.core.config.OriginGateConfig.CountryMode;
import io.github.origingate.core.config.OriginGateConfig.FailureMode;
import io.github.origingate.core.lookup.IpInfo;
import io.github.origingate.core.lookup.LookupService;
import io.github.origingate.core.net.AddressRange;
import io.github.origingate.core.net.Addresses;
import io.github.origingate.core.rules.Decision.Outcome;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether a connection may join. Order: global bypass, deny-addresses, private-address skip,
 * lookup, then the deny-networks, vpn, proxy, hosting, and country rules. The first rule that matches decides.
 */
public final class Gate {
    /** Added to name and permission bypasses where names are not checked, for the log line and staff alert. */
    public static final String UNVERIFIED_NAME = "anyone can claim this name in offline mode";

    private final OriginGateConfig config;
    private final LookupService lookups;
    private final Log log;
    private final Set<String> permissionNames;
    private final String bypassCaution;

    public Gate(OriginGateConfig config, LookupService lookups, Log log) {
        this(config, lookups, log, false);
    }

    /** {@code namesUnverified}: offline mode or BungeeCord IP forwarding, where anyone can join with any name. */
    public Gate(OriginGateConfig config, LookupService lookups, Log log, boolean namesUnverified) {
        this.config = config;
        this.lookups = lookups;
        this.log = log;
        this.bypassCaution = namesUnverified ? UNVERIFIED_NAME : null;
        Set<String> names = new LinkedHashSet<>(config.bypass().permissions());
        for (List<String> rule : config.rules().bypassPermissions()) names.addAll(rule);
        this.permissionNames = Collections.unmodifiableSet(names);
    }

    /** Every permission these checks read, so a platform can look them up before checking on another thread. */
    public Set<String> permissionNames() {
        return permissionNames;
    }

    /** Runs every check. The future never completes exceptionally; failures follow {@code on-lookup-failure}. */
    public CompletableFuture<Decision> check(LoginAttempt attempt) {
        Optional<Decision> early;
        try {
            early = beforeLookup(attempt);
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(failed(attempt, ex));
        }
        if (early.isPresent()) return CompletableFuture.completedFuture(early.get());
        String ip = Addresses.text(attempt.address());
        CompletableFuture<LookupService.Result> lookup;
        try {
            lookup = lookups.lookup(ip, false, Duration.ofMillis(config.lookup().waitMillis()));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(failed(attempt, ex));
        }
        return io.github.origingate.core.util.Futures.timeout(lookup, config.lookup().waitMillis(), TimeUnit.MILLISECONDS)
                .handle((result, error) -> decide(attempt, result, error));
    }

    /** Final identity and permissions are checked even when an earlier lookup failed. */
    public Decision finish(LoginAttempt attempt, LookupService.Result result, Throwable error) {
        try {
            Optional<Decision> early = beforeLookup(attempt);
            if (early.isPresent()) return early.get();
        } catch (RuntimeException ex) {
            return failed(attempt, ex);
        }
        return decide(attempt, result, error);
    }

    /** For an attempt that {@link #beforeLookup} has already let through to the lookup. */
    private Decision decide(LoginAttempt attempt, LookupService.Result result, Throwable error) {
        try {
            if (error != null) return failed(attempt, error);
            if (result == null) return failed(attempt, new IllegalStateException("No lookup result"));
            return afterLookup(attempt, result);
        } catch (RuntimeException ex) {
            return failed(attempt, ex);
        }
    }

    /** Checks that need no lookup. Empty means a lookup is needed. */
    public Optional<Decision> beforeLookup(LoginAttempt attempt) {
        InetAddress address = attempt.address();
        OriginGateConfig.Bypass bypass = config.bypass();
        if (bypass.players().contains(attempt.username().toLowerCase(Locale.ROOT))
                || (attempt.uuid() != null && bypass.players().contains(attempt.uuid().toString()))) {
            return Optional.of(allow("player is on the bypass list", null, bypassCaution));
        }
        if (contains(bypass.addresses(), address)) return Optional.of(allow("address is on the bypass list", null));
        if (attempt.hasAny(bypass.permissions())) {
            return Optional.of(allow("player has a global bypass permission", null, bypassCaution));
        }

        OriginGateConfig.AddressRule deny = config.rules().denyAddresses();
        if (deny.enabled() && contains(deny.list(), address)) {
            return Optional.of(matched(attempt, Rule.DENY_ADDRESSES, deny.bypassPermissions(), null, "address is on the deny list"));
        }
        if (config.lookup().skipPrivateAddresses() && Addresses.isPrivate(address)) {
            return Optional.of(allow("private address, lookup skipped", null));
        }
        return Optional.empty();
    }

    /** A rule that matched lookup data, before bypass permissions are considered. */
    public static final class Match {
        private final Rule rule;
        private final List<String> bypassPermissions;
        private final String note;

        Match(Rule rule, List<String> bypassPermissions, String note) {
            this.rule = rule;
            this.bypassPermissions = bypassPermissions;
            this.note = note;
        }

        public Rule rule() { return rule; }
        public List<String> bypassPermissions() { return bypassPermissions; }
        public String note() { return note; }
    }

    /**
     * The first of the deny-networks, vpn, proxy, hosting, and country rules that matches {@code info}, or empty.
     * A match kicks unless the player has a bypass permission, so the lookup cache shares only such answers
     * across an IPv6 /64.
     */
    public static Optional<Match> match(OriginGateConfig.Rules rules, IpInfo info) {
        OriginGateConfig.NetworkRule networks = rules.denyNetworks();
        if (networks.enabled()) {
            Optional<String> listed = listedNetwork(info, networks);
            if (listed.isPresent()) {
                return Optional.of(new Match(Rule.DENY_NETWORKS, networks.bypassPermissions(),
                        "network is on the deny list (" + listed.get() + ")"));
            }
        }
        if (rules.vpn().enabled() && info.vpn()) return Optional.of(new Match(Rule.VPN, rules.vpn().bypassPermissions(), "flagged as VPN"));
        if (rules.proxy().enabled() && info.proxy() && !Countries.matchesAny(info, rules.proxy().allowedCountries())) {
            return Optional.of(new Match(Rule.PROXY, rules.proxy().bypassPermissions(), "flagged as proxy"));
        }
        if (rules.hosting().enabled() && info.hosting()) {
            return Optional.of(new Match(Rule.HOSTING, rules.hosting().bypassPermissions(), "hosting or datacenter network"));
        }
        OriginGateConfig.CountryRule country = rules.country();
        if (country.enabled()) {
            boolean listed = Countries.matchesAny(info, country.countries());
            if (country.mode() == CountryMode.ALLOWLIST ? !listed : listed) {
                return Optional.of(new Match(Rule.COUNTRY, country.bypassPermissions(),
                        country.mode() == CountryMode.ALLOWLIST ? "country not on the allowlist" : "country on the denylist"));
            }
        }
        return Optional.empty();
    }

    /** Applies the deny-networks, vpn, proxy, hosting, and country rules to a finished lookup. */
    public Decision afterLookup(LoginAttempt attempt, LookupService.Result result) {
        IpInfo info = result.info();
        Optional<Match> match = match(config.rules(), info);
        log.debug("Rules for " + info.ip() + " (vpn " + yesNo(info.vpn()) + ", proxy " + yesNo(info.proxy()) + ", hosting "
                + yesNo(info.hosting()) + ", country " + Text.dash(info.countryCode()) + ", " + Text.dash(info.asn()) + "): "
                + (match.isPresent() ? match.get().rule().id() + " matched" : "none matched"));
        if (!match.isPresent()) return allow("no rule matched", result);
        Match matched = match.get();
        return matched(attempt, matched.rule(), matched.bypassPermissions(), result, matched.note());
    }

    private Decision matched(LoginAttempt attempt, Rule rule, List<String> bypassPermissions,
                             LookupService.Result result, String note) {
        boolean bypassed = attempt.hasAny(bypassPermissions);
        log.debug("Rule " + rule.id() + " matched (" + note + "). Bypass permission? " + yesNo(bypassed));
        return new Decision(bypassed ? Outcome.BYPASS : Outcome.DENY, rule, note, result, config.dryRun(),
                bypassed ? bypassCaution : null);
    }

    private Decision allow(String note, LookupService.Result result) {
        return allow(note, result, null);
    }

    private Decision allow(String note, LookupService.Result result, String caution) {
        return new Decision(Outcome.ALLOW, null, note, result, config.dryRun(), caution);
    }

    private Decision failed(LoginAttempt attempt, Throwable error) {
        // The address's own lookup could not complete. An answer a rule acts on, kept for its IPv6 /64, decides
        // instead of on-lookup-failure, so a flood that fills the queue does not let a rotating VPN user in.
        Optional<LookupService.Result> fallback;
        try {
            fallback = lookups.networkFallback(Addresses.text(attempt.address()));
        } catch (RuntimeException ex) {
            fallback = Optional.empty();
        }
        if (fallback.isPresent()) {
            log.debug("Lookup for " + attempt.username() + " failed (" + Text.message(error) + "); using the answer kept for its /64");
            return afterLookup(attempt, fallback.get());
        }
        Throwable cause = Text.unwrap(error);
        boolean overBudget = cause instanceof LookupService.BudgetExceededException;
        String reason = cause instanceof TimeoutException
                ? "lookup took longer than " + config.lookup().waitMillis() + " ms"
                : "lookup failed: " + Text.message(error);
        // Over the budget, on-budget-exceeded decides; when it is not set it is the same as on-lookup-failure.
        FailureMode mode = overBudget ? config.lookup().onBudgetExceeded() : config.lookup().onFailure();
        boolean deny = mode == FailureMode.DENY;
        log.debug("Lookup for " + attempt.username() + " failed (" + reason + "), "
                + (overBudget ? "on-budget-exceeded" : "on-lookup-failure") + " is " + (deny ? "deny" : "allow"));
        return new Decision(deny ? Outcome.DENY : Outcome.ALLOW, Rule.LOOKUP_FAILURE, reason, null, config.dryRun());
    }

    /** The listed ASN or organisation text that matches, compared with the ASN, provider, organisation, and operator. */
    static Optional<String> listedNetwork(IpInfo info, OriginGateConfig.NetworkRule rule) {
        if (info.asn() != null) {
            String asn = info.asn().toUpperCase(Locale.ROOT);
            if (!asn.startsWith("AS")) asn = "AS" + asn;
            if (rule.asns().contains(asn)) return Optional.of(asn);
        }
        for (String name : new String[] {info.provider(), info.organisation(), info.operatorName()}) {
            if (name == null) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            for (String listed : rule.organisations()) if (lower.contains(listed)) return Optional.of(listed);
        }
        return Optional.empty();
    }

    private static boolean contains(List<AddressRange> ranges, InetAddress address) {
        for (AddressRange range : ranges) if (range.contains(address)) return true;
        return false;
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    public OriginGateConfig config() {
        return config;
    }

    public LookupService lookups() {
        return lookups;
    }
}
