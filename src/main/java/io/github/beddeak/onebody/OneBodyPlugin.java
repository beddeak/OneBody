package io.github.beddeak.onebody;

import io.github.beddeak.onebody.command.OneBodyCommand;
import io.github.beddeak.onebody.config.ConfigManager;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.paper.ActionListener;
import io.github.beddeak.onebody.paper.InteractionListener;
import io.github.beddeak.onebody.paper.PaperGameRuntime;
import io.github.beddeak.onebody.paper.ParticipantListener;
import io.github.beddeak.onebody.paper.MovementListener;
import io.github.beddeak.onebody.paper.DamageEventGuard;
import io.github.beddeak.onebody.paper.DamageActionListener;
import io.github.beddeak.onebody.paper.SharedInventoryManager;
import io.github.beddeak.onebody.paper.SharedItemUseListener;
import io.github.beddeak.onebody.paper.SharedVitalsManager;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class OneBodyPlugin extends JavaPlugin {
    private GameManager game;
    private SharedInventoryManager sharedInventory;
    private SharedVitalsManager sharedVitals;
    private MovementListener movement;

    @Override public void onEnable() {
        saveDefaultConfig();
        ConfigManager config = new ConfigManager(this);
        try {
            config.reload();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Invalid OneBody config; plugin disabled", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        DamageEventGuard damageGuard = new DamageEventGuard();
        PaperGameRuntime runtime = new PaperGameRuntime(this, config, damageGuard);
        game = new GameManager(runtime, config.settings());
        sharedInventory = new SharedInventoryManager(this, game);
        sharedVitals = new SharedVitalsManager(this, game, player -> damageGuard.active());
        movement = new MovementListener(game, this);
        runtime.sharingLifecycle(() -> {
            movement.stop();
            sharedVitals.stop();
            sharedInventory.stop();
            var leader = game.eligibleParticipants().stream().findFirst().orElseThrow();
            if (config.flag("sharing.inventory") && !sharedInventory.start(leader)) {
                throw new IllegalStateException("Shared inventory could not be safely backed up");
            }
            sharedVitals.start(leader, config.flag("sharing.health"), config.flag("sharing.hunger"));
            movement.start();
        }, () -> {
            movement.stop();
            sharedVitals.stop();
            sharedInventory.stop();
        }, () -> {
            movement.stop();
            sharedVitals.rollbackStart();
            sharedInventory.stop();
        }, () -> sharedVitals.commitStart());
        game.synchronizeParticipants();
        OneBodyCommand executor = new OneBodyCommand(this, game, config, runtime);
        var command = Objects.requireNonNull(getCommand("onebody"), "Missing onebody command descriptor");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new ActionListener(game), this);
        getServer().getPluginManager().registerEvents(new InteractionListener(game), this);
        getServer().getPluginManager().registerEvents(movement, this);
        getServer().getPluginManager().registerEvents(new ParticipantListener(game), this);
        getServer().getPluginManager().registerEvents(new DamageActionListener(game, damageGuard), this);
        getServer().getPluginManager().registerEvents(sharedVitals, this);
        getServer().getPluginManager().registerEvents(sharedInventory, this);
        getServer().getPluginManager().registerEvents(new SharedItemUseListener(sharedInventory), this);
        for (var player : getServer().getOnlinePlayers()) {
            sharedInventory.playerConnected(player);
        }
        getLogger().info("일심동체 / mod by beddeak(침댁) enabled");
    }

    @Override public void onDisable() {
        try {
            if (game != null) { game.shutdown(); }
        } finally {
            game = null;
            if (movement != null) { movement.stop(); }
            try {
                if (sharedVitals != null) { sharedVitals.shutdown(); }
            } finally {
                try {
                    if (sharedInventory != null) { sharedInventory.shutdown(); }
                } finally {
                    getServer().getScheduler().cancelTasks(this);
                    HandlerList.unregisterAll(this);
                }
            }
        }
    }
}
