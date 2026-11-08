package ru.terracraft.origingate.core;

import ru.terracraft.origingate.core.lookup.IpInfo;
import ru.terracraft.origingate.core.lookup.LookupService;
import ru.terracraft.origingate.core.net.Addresses;
import ru.terracraft.origingate.core.report.DecisionLine;
import ru.terracraft.origingate.core.rules.Decision;
import ru.terracraft.origingate.core.rules.LoginAttempt;
import ru.terracraft.origingate.core.util.Futures;

import java.net.InetAddress;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** {@code /origingate check|status|reload|cache clear}, independent of the platform. */
public final class Commands {
    public static final String CHECK = "origingate.command.check";
    public static final String RELOAD = "origingate.command.reload";
    public static final String CACHE = "origingate.command.cache";
    public static final String STATUS = "origingate.command.status";
    /** Every command permission, for platforms that look them up before running a command elsewhere. */
    public static final List<String> PERMISSIONS = List.of(CHECK, STATUS, RELOAD, CACHE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public interface Sender {
        boolean hasPermission(String permission);

        /** May be called from any thread. */
        void reply(String line);
    }

    public interface Platform {
        /** Null when OriginGate failed to start. */
        OriginGateRuntime runtime();

        /** An online player's connection, found by name. */
        Optional<LoginAttempt> onlinePlayer(String name);

        List<String> onlinePlayerNames();

        /** Loads the files again. Returns the message to show. */
        String reload();
    }

    private final Platform platform;

    public Commands(Platform platform) {
        this.platform = platform;
    }

    public static boolean canUse(Sender sender) {
        for (String permission : PERMISSIONS) if (sender.hasPermission(permission)) return true;
        return false;
    }

    public void execute(String[] args, Sender sender) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "check": {
                if (!allowed(sender, CHECK)) return;
                if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equalsIgnoreCase("refresh"))) {
                    sender.reply("Usage: /origingate check <player|ip> [refresh]");
                    return;
                }
                check(args[1], args.length == 3, sender);
                break;
            }
            case "status": {
                if (!allowed(sender, STATUS)) return;
                OriginGateRuntime runtime = running(sender);
                if (runtime == null) return;
                sender.reply("OriginGate " + OriginGateRuntime.VERSION + " status:");
                for (String line : runtime.status()) sender.reply("  " + line);
                break;
            }
            case "reload": {
                if (!allowed(sender, RELOAD)) return;
                sender.reply(platform.reload());
                break;
            }
            case "cache": {
                if (!allowed(sender, CACHE)) return;
                if (args.length != 3 || !args[1].equalsIgnoreCase("clear")) {
                    sender.reply("Usage: /origingate cache clear <ip|all>");
                    return;
                }
                clear(args[2], sender);
                break;
            }
            default: usage(sender);
        }
    }

    public List<String> suggest(String[] args, Sender sender) {
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            if (sender.hasPermission(CHECK)) options.add("check");
            if (sender.hasPermission(STATUS)) options.add("status");
            if (sender.hasPermission(RELOAD)) options.add("reload");
            if (sender.hasPermission(CACHE)) options.add("cache");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check") && sender.hasPermission(CHECK)) {
            options.addAll(platform.onlinePlayerNames());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("check") && sender.hasPermission(CHECK)) {
            options.add("refresh");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("cache") && sender.hasPermission(CACHE)) {
            options.add("clear");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("cache") && sender.hasPermission(CACHE)) {
            options.add("all");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private void check(String target, boolean refresh, Sender sender) {
        OriginGateRuntime runtime = running(sender);
        if (runtime == null) return;
        Optional<LoginAttempt> player = platform.onlinePlayer(target);
        LoginAttempt attempt;
        if (player.isPresent()) {
            attempt = player.get();
        } else {
            Optional<InetAddress> address = Addresses.parse(target);
            if (!address.isPresent()) {
                sender.reply(target + " is not an online player or an IP address.");
                return;
            }
            attempt = LoginAttempt.address(address.get());
        }
        String ip = Addresses.text(attempt.address());
        String who = player.isPresent() ? attempt.username() + " (" + ip + ")" : ip;
        if (runtime.config().lookup().skipPrivateAddresses() && Addresses.isPrivate(attempt.address())) {
            sender.reply(who + " is a private address. Lookups are skipped (skip-private-addresses).");
            runtime.gate().beforeLookup(attempt).ifPresent(decision -> sender.reply("Result: " + result(decision, player.isPresent())));
            return;
        }
        sender.reply("Looking up " + who + (refresh ? " from the provider..." : "..."));
        CompletableFuture<LookupService.Result> lookup = runtime.gate().lookups().lookup(ip, refresh);
        Futures.timeout(lookup, 30, TimeUnit.SECONDS).whenComplete((result, error) -> {
            if (error != null) {
                sender.reply("Lookup failed for " + ip + ": " + Text.message(error));
                return;
            }
            IpInfo info = result.info();
            sender.reply("OriginGate check for " + who + ":");
            sender.reply("  Source: " + result.source().name().toLowerCase(Locale.ROOT) + ", checked "
                    + TIME.format(info.checkedAt().atZone(ZoneId.systemDefault())));
            if (result.answeredBy() != null) sender.reply("  Answered by: " + result.answeredBy().described());
            sender.reply("  Provider: " + Text.dash(info.provider()) + " | Organisation: " + Text.dash(info.organisation())
                    + " | Operator: " + Text.dash(info.operatorName()));
            sender.reply("  ASN: " + Text.dash(info.asn()) + " | Type: " + Text.dash(info.type()));
            sender.reply("  Location: " + Text.dash(info.city()) + ", " + Text.dash(info.region()) + ", " + Text.dash(info.country())
                    + " (" + Text.dash(info.countryCode()) + ")");
            sender.reply("  VPN: " + (info.vpn() ? "yes" : "no") + " | Proxy: " + (info.proxy() ? "yes" : "no"));
            Decision decision;
            try {
                decision = runtime.gate().beforeLookup(attempt).orElseGet(() -> runtime.gate().afterLookup(attempt, result));
            } catch (RuntimeException ex) {
                sender.reply("  Result: could not be worked out: " + ex.getMessage());
                return;
            }
            sender.reply("  Result" + (player.isPresent() ? "" : " for a player without bypass permissions") + ": "
                    + result(decision, player.isPresent()));
        });
    }

    private static String result(Decision decision, boolean player) {
        String rule = decision.rule() == null ? "" : " by the " + decision.rule().id() + " rule";
        String line;
        switch (decision.outcome()) {
            case ALLOW: line = "allowed"; break;
            case BYPASS: line = "allowed through a bypass" + rule; break;
            case DENY: line = "kicked" + rule; break;
            default: throw new AssertionError(decision.outcome());
        }
        if (decision.outcome() == Decision.Outcome.DENY && decision.dryRun()) line += " (dry run: would kick, lets in)";
        return line + (decision.note() == null ? "" : " (" + decision.note() + ")") + " [" + DecisionLine.label(decision) + "]";
    }

    private void clear(String target, Sender sender) {
        OriginGateRuntime runtime = running(sender);
        if (runtime == null) return;
        String key;
        if (target.equalsIgnoreCase("all")) {
            key = "all";
        } else {
            Optional<InetAddress> address = Addresses.parse(target);
            if (!address.isPresent()) {
                sender.reply(target + " is not an IP address. Use an IP or all.");
                return;
            }
            key = Addresses.text(address.get());
        }
        runtime.clearCache(key).whenComplete((message, error) -> {
            if (error == null) sender.reply(message);
            else sender.reply("Could not clear the cache: " + Text.message(error));
        });
    }

    private OriginGateRuntime running(Sender sender) {
        OriginGateRuntime runtime = platform.runtime();
        if (runtime == null) sender.reply("OriginGate is not running. Fix the error in the console, then run /origingate reload.");
        return runtime;
    }

    private static boolean allowed(Sender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.reply("You do not have permission to use this command.");
        return false;
    }

    private static void usage(Sender sender) {
        sender.reply("OriginGate " + OriginGateRuntime.VERSION + " commands:");
        if (sender.hasPermission(CHECK)) sender.reply("  /origingate check <player|ip> [refresh]");
        if (sender.hasPermission(STATUS)) sender.reply("  /origingate status");
        if (sender.hasPermission(RELOAD)) sender.reply("  /origingate reload");
        if (sender.hasPermission(CACHE)) sender.reply("  /origingate cache clear <ip|all>");
    }
}
