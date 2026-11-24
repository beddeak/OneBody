package io.github.beddeak.onebody.command;

import io.github.beddeak.onebody.config.ConfigManager;
import io.github.beddeak.onebody.game.ActiveSyncChallenge;
import io.github.beddeak.onebody.game.ChallengeState;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.paper.PaperGameRuntime;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class OneBodyCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final GameManager game;
    private final ConfigManager config;
    private final PaperGameRuntime runtime;

    public OneBodyCommand(JavaPlugin plugin, GameManager game, ConfigManager config, PaperGameRuntime runtime) {
        this.plugin = plugin;
        this.game = game;
        this.config = config;
        this.runtime = runtime;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(Component.text("/" + label + " " + String.join("|", allowed(sender))));
            return true;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!List.of("start", "stop", "status", "reload").contains(subcommand)) {
            sender.sendMessage(Component.text("/" + label + " " + String.join("|", allowed(sender))));
            return true;
        }
        if (!sender.hasPermission("onebody.admin")) {
            sender.sendMessage(config.prefixed("no-permission"));
            return true;
        }
        switch (subcommand) {
            case "start" -> {
                try {
                    if (!game.start()) { sender.sendMessage(config.prefixed("too-few")); }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "OneBody sharing initialization failed", exception);
                    sender.sendMessage(config.prefixed("start-failed"));
                }
            }
            case "stop" -> game.stop();
            case "status" -> status(sender);
            case "reload" -> {
                try {
                    config.reload();
                    game.settings(config.settings());
                    if (!config.flag("actionbar.enabled")) { runtime.clearBars(game.participants()); }
                    sender.sendMessage(config.prefixed("reloaded"));
                } catch (Exception exception) {
                    plugin.getLogger().log(Level.WARNING, "OneBody config reload failed; retaining previous settings", exception);
                    sender.sendMessage(config.prefixed("reload-failed"));
                }
            }
            default -> throw new IllegalStateException(subcommand);
        }
        return true;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("[일심동체] 게임 활성화: " + game.running()));
        sender.sendMessage(Component.text("참가자 " + game.participants().size() + "명: "
                + game.participants().stream().map(runtime::name).collect(Collectors.joining(", "))));
        ActiveSyncChallenge challenge = game.activeChallenge();
        sender.sendMessage(Component.text("현재 Challenge: " + (challenge != null)));
        if (challenge == null) {
            sender.sendMessage(Component.text("SyncAction: 없음 | 완료: 0 | 미완료: 0 | punishment: false"));
        } else {
            sender.sendMessage(Component.text("SyncAction: " + challenge.action() + " | 상태: " + challenge.state()));
            if (challenge.movementInput() != null) {
                sender.sendMessage(Component.text("기준 이동 상태: " + challenge.movementInput().display()
                        + " (방향 무관, 전원 이동 또는 전원 정지 시 복구)"));
            }
            sender.sendMessage(Component.text("완료 " + challenge.completedPlayers().size() + "명: "
                    + challenge.completedPlayers().stream().map(runtime::name).collect(Collectors.joining(", "))));
            sender.sendMessage(Component.text("미완료 " + challenge.pendingPlayers().size() + "명: "
                    + challenge.pendingPlayers().stream().map(runtime::name).collect(Collectors.joining(", "))));
            sender.sendMessage(Component.text("punishment: " + (challenge.state() == ChallengeState.PUNISHING)
                    + " | 시작 tick: " + challenge.startTick()));
        }
    }

    private List<String> allowed(CommandSender sender) {
        return sender.hasPermission("onebody.admin") ? List.of("start", "stop", "status", "reload") : List.of();
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length != 1 ? List.of() : allowed(sender).stream()
                .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
