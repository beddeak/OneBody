package io.github.beddeak.onebody.paper;

import io.github.beddeak.onebody.config.ConfigManager;
import io.github.beddeak.onebody.game.ActiveSyncChallenge;
import io.github.beddeak.onebody.game.GameRuntime;
import io.github.beddeak.onebody.game.MovementInput;
import io.github.beddeak.onebody.game.ChallengeState;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperGameRuntime implements GameRuntime {
    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final DamageEventGuard damageGuard;
    private Runnable sharingStarted = () -> { };
    private Runnable sharingStopped = () -> { };
    private Runnable sharingStartFailed = () -> { };
    private Runnable sharingStartCommitted = () -> { };

    public PaperGameRuntime(JavaPlugin plugin, ConfigManager config, DamageEventGuard damageGuard) {
        this.plugin = plugin;
        this.config = config;
        this.damageGuard = damageGuard;
    }

    public void sharingLifecycle(Runnable started, Runnable stopped, Runnable failed, Runnable committed) {
        sharingStarted = started;
        sharingStopped = stopped;
        sharingStartFailed = failed;
        sharingStartCommitted = committed;
    }

    @Override public long currentTick() { return Integer.toUnsignedLong(Bukkit.getCurrentTick()); }

    @Override public Set<UUID> onlinePlayers() {
        Set<UUID> players = new LinkedHashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(player.getUniqueId());
        }
        return players;
    }

    @Override public MovementInput movementInput(UUID id) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) { return MovementInput.NONE; }
        var input = player.getCurrentInput();
        return new MovementInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight());
    }

    @Override public boolean eligible(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline() && !player.isDead() && player.getHealth() > 0
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    @Override public Task later(Runnable callback, long delayTicks) {
        var task = Bukkit.getScheduler().runTaskLater(plugin, callback, delayTicks);
        return task::cancel;
    }

    @Override public Task repeat(Runnable callback, long delayTicks, long intervalTicks) {
        var task = Bukkit.getScheduler().runTaskTimer(plugin, callback, delayTicks, intervalTicks);
        return task::cancel;
    }

    @Override public void damage(UUID id, double amount) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && eligible(id)) {
            damageGuard.runPenalty(() -> PenaltyDamage.apply(player, amount));
        }
    }

    @Override public void started(Set<UUID> players) {
        try {
            sharingStarted.run();
            each(players, player -> {
                player.showTitle(config.startupTitle());
                player.sendMessage(config.prefixed("game-started"));
            });
            sharingStartCommitted.run();
        } catch (RuntimeException exception) {
            sharingStartFailed.run();
            throw exception;
        }
    }

    @Override public void stopped(Set<UUID> players) {
        sharingStopped.run();
        each(players, player -> player.sendMessage(config.prefixed("game-stopped")));
    }

    @Override public void outcome(Set<UUID> players, boolean success) {
        String key = success ? "success" : "failed";
        each(players, player -> {
            if (config.flag("messages." + key + "-enabled")) {
                player.sendMessage(config.prefixed(key));
            }
            if (config.flag("actionbar.enabled")) {
                player.sendActionBar(config.message(key));
            }
        });
    }

    @Override public void punishmentBar(ActiveSyncChallenge challenge) {
        if (challenge.state() != ChallengeState.PUNISHING || !config.flag("actionbar.enabled")) {
            return;
        }
        Component bar = config.message("required-action").append(config.actionName(challenge.action()));
        if (config.flag("actionbar.reveal-players")) {
            String names = challenge.pendingPlayers().stream().map(this::name).collect(Collectors.joining(", "));
            bar = bar.append(Component.text(" 미완료: " + names));
        }
        Component finalBar = bar;
        each(challenge.players(), player -> player.sendActionBar(finalBar));
    }

    public String name(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player == null ? id.toString() : player.getName();
    }

    @Override public void clearBars(Set<UUID> players) {
        each(players, player -> player.sendActionBar(Component.empty()));
    }

    private void each(Set<UUID> players, Consumer<Player> callback) {
        for (UUID id : Set.copyOf(players)) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                callback.accept(player);
            }
        }
    }
}
