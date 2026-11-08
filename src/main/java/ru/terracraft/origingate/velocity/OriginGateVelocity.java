package ru.terracraft.origingate.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import ru.terracraft.origingate.core.Commands;
import ru.terracraft.origingate.core.Log;
import ru.terracraft.origingate.core.OfflineModeWarning;
import ru.terracraft.origingate.presentation.MessagesRenderer;
import ru.terracraft.origingate.core.Text;
import ru.terracraft.origingate.core.OriginGateRuntime;
import ru.terracraft.origingate.core.RuntimeController;
import ru.terracraft.origingate.core.rules.Decision;
import ru.terracraft.origingate.core.rules.LoginAttempt;
import net.kyori.adventure.text.Component;
import org.bstats.velocity.Metrics;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Plugin(id = "origingate", name = "OriginGate", version = "2.0.1",
        description = "Checks where a connection comes from (VPN, proxy, country) before it joins",
        authors = {"Kaizen Network", "luci4ka"})
public final class OriginGateVelocity {
    private static final int BSTATS_ID = 35142;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Metrics.Factory metricsFactory;
    private final RuntimeController controller;
    private final Map<UUID, Component> bypassNotices = new ConcurrentHashMap<>();
    private Metrics metrics;

    @Inject public OriginGateVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory,
                                      Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.logger = logger;
        this.metricsFactory = metricsFactory;
        this.controller = new RuntimeController(dataDirectory, new Log() {
            @Override public void info(String message) { logger.info(message); }
            @Override public void warn(String message, Throwable cause) {
                if (cause == null) logger.warn(message);
                else logger.warn(message, cause);
            }
            @Override public void error(String message, Throwable cause) {
                if (cause == null) logger.error(message);
                else logger.error(message, cause);
            }
            @Override public void debug(String message) { logger.info(message); }
        }, () -> proxy.getConfiguration().isOnlineMode() ? OfflineModeWarning.Mode.ONLINE : OfflineModeWarning.Mode.OFFLINE,
                this::staffAlert);
    }

    /** Lookup alerts come from worker threads; Velocity's sendMessage is safe from any thread. */
    private void staffAlert(OriginGateRuntime active, String template, String message) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        Component text;
        try {
            text = MessagesRenderer.renderAlert(template, message);
        } catch (RuntimeException ex) {
            logger.warn("Could not build the staff alert: {}", Text.message(ex));
            return;
        }
        sendToStaff(active, text);
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        controller.reload();
        var commands = new Commands(new Commands.Platform() {
            @Override public OriginGateRuntime runtime() { return controller.runtime(); }
            @Override public Optional<LoginAttempt> onlinePlayer(String name) {
                return proxy.getPlayer(name).flatMap(OriginGateVelocity.this::attempt);
            }
            @Override public List<String> onlinePlayerNames() {
                return proxy.getAllPlayers().stream().map(Player::getUsername).toList();
            }
            @Override public String reload() { return controller.reload(); }
        });
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("origingate").plugin(this).build(),
                new OriginGateCommand(commands));
        proxy.getScheduler().buildTask(this, controller::cleanup)
                .delay(Duration.ofMinutes(1)).repeat(Duration.ofHours(1)).schedule();
        metrics = metricsFactory.make(this, BSTATS_ID);
    }

    /**
     * Runs after LuckPerms: its user data is loaded during PermissionsSetupEvent, which Velocity fires
     * before LoginEvent, and its own LoginEvent handlers use FIRST and NORMAL order (higher runs first).
     */
    @Subscribe(priority = -100) public EventTask onLogin(LoginEvent event) {
        OriginGateRuntime active = controller.runtime();
        if (active == null || !event.getResult().isAllowed()) return null;
        Optional<LoginAttempt> attempt = attempt(event.getPlayer());
        if (attempt.isEmpty()) {
            logger.warn("No IP address for {}, not checked", event.getPlayer().getUsername());
            return null;
        }
        return EventTask.resumeWhenComplete(active.gate().check(attempt.get())
                .thenAccept(decision -> apply(active, event, attempt.get(), decision))
                .exceptionally(ex -> {
                    logger.error("OriginGate check failed for {}", event.getPlayer().getUsername(), ex);
                    return null;
                }));
    }

    private void apply(OriginGateRuntime active, LoginEvent event, LoginAttempt attempt, Decision decision) {
        controller.report(active, attempt, decision);
        if (decision.dryRun()) return;
        var messages = active.messages();
        if (decision.kicks()) {
            Component reason;
            try {
                reason = render(messages.kick(decision.rule()), attempt, decision);
            } catch (RuntimeException ex) {
                // Never let a broken message turn a kick into a join.
                logger.warn("Could not build the kick message for {}: {}", decision.rule().id(), Text.message(ex));
                reason = Component.text("You cannot join this server.");
            }
            event.setResult(ResultedEvent.ComponentResult.denied(reason));
            alert(active, messages.alertDenied(), attempt, decision);
        } else if (decision.outcome() == Decision.Outcome.BYPASS) {
            if (!messages.bypassNotice().isEmpty()) {
                try {
                    bypassNotices.put(attempt.uuid(), render(messages.bypassNotice(), attempt, decision));
                } catch (RuntimeException ex) {
                    // The player is let in either way; a broken notice must not cost the staff alert.
                    logger.warn("Could not build the bypass notice: {}", Text.message(ex));
                }
            }
            alert(active, messages.alertBypassed(), attempt, decision);
        }
    }

    private void alert(OriginGateRuntime active, String template, LoginAttempt attempt, Decision decision) {
        if (template.isEmpty() || active.config().alertPermissions().isEmpty()) return;
        Component message;
        try {
            message = MessagesRenderer.withCaution(render(template, attempt, decision), decision);
        } catch (RuntimeException ex) {
            logger.warn("Could not build the staff alert: {}", Text.message(ex));
            return;
        }
        sendToStaff(active, message);
    }

    private void sendToStaff(OriginGateRuntime active, Component message) {
        for (Player player : proxy.getAllPlayers()) {
            for (String permission : active.config().alertPermissions()) {
                if (player.hasPermission(permission)) {
                    player.sendMessage(message);
                    break;
                }
            }
        }
    }

    /** Chat is not available during login, so the bypass notice waits for the first server. */
    @Subscribe public void onServerConnected(ServerPostConnectEvent event) {
        if (event.getPreviousServer() != null) return;
        Component notice = bypassNotices.remove(event.getPlayer().getUniqueId());
        if (notice != null) event.getPlayer().sendMessage(notice);
    }

    @Subscribe public void onDisconnect(DisconnectEvent event) {
        bypassNotices.remove(event.getPlayer().getUniqueId());
    }

    /** Velocity waits for shutdown listeners, so the runtime closes and queued log lines are written first. */
    @Subscribe public void onShutdown(ProxyShutdownEvent event) {
        if (metrics != null) metrics.shutdown();
        controller.close();
    }

    private Optional<LoginAttempt> attempt(Player player) {
        InetSocketAddress remote = player.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        if (address == null) return Optional.empty();
        return Optional.of(new LoginAttempt(player.getUsername(), player.getUniqueId(), address, player::hasPermission));
    }

    static Component render(String template, LoginAttempt attempt, Decision decision) {
        return MessagesRenderer.render(template, attempt, decision);
    }
}
