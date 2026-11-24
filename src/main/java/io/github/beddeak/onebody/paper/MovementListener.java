package io.github.beddeak.onebody.paper;

import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Samples motion, sneaking and sprinting as whole frames, including held states and releases. */
public final class MovementListener implements Listener {
    private static final double MINIMUM_DISTANCE_SQUARED = 0.000001;
    private static final double MAXIMUM_DISTANCE_SQUARED = 16;
    private static final int STOP_AFTER_TICKS = 3;
    private final GameManager game;
    private final Host host;
    private final Map<UUID, Location> previous = new LinkedHashMap<>();
    private final Map<UUID, Long> lastMotion = new LinkedHashMap<>();
    private final Map<UUID, Long> externallyMovedUntil = new LinkedHashMap<>();
    private BukkitTask task;

    public MovementListener(GameManager game, JavaPlugin plugin) {
        this(game, new Host() {
            @Override public Player player(UUID id) { return plugin.getServer().getPlayer(id); }
            @Override public long tick() { return Integer.toUnsignedLong(Bukkit.getCurrentTick()); }
            @Override public BukkitTask repeat(Runnable callback) {
                return plugin.getServer().getScheduler().runTaskTimer(plugin, callback, 1L, 1L);
            }
        });
    }

    MovementListener(GameManager game, Host host) { this.game = game; this.host = host; }

    public void start() {
        stop();
        for (UUID id : game.eligibleParticipants()) {
            Player player = host.player(id);
            if (player != null) { previous.put(id, player.getLocation().clone()); }
        }
        task = host.repeat(this::sample);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        previous.clear();
        lastMotion.clear();
        externallyMovedUntil.clear();
    }

    void sample() {
        if (!game.running()) { stop(); return; }
        Set<UUID> players = game.eligibleParticipants();
        previous.keySet().retainAll(players);
        lastMotion.keySet().retainAll(players);
        externallyMovedUntil.keySet().retainAll(players);
        Map<UUID, Boolean> moving = new LinkedHashMap<>();
        Map<UUID, Boolean> sneaking = new LinkedHashMap<>();
        Map<UUID, Boolean> sprinting = new LinkedHashMap<>();
        for (UUID id : players) {
            Player player = host.player(id);
            if (player == null) { continue; }
            sneaking.put(id, player.isSneaking());
            sprinting.put(id, player.isSprinting());
            Location now = player.getLocation();
            Location before = previous.put(id, now.clone());
            boolean sameWorld = before != null && before.getWorld() == now.getWorld();
            if (!sameWorld || player.isInsideVehicle()) { lastMotion.remove(id); }
            double dx = sameWorld ? now.getX() - before.getX() : 0;
            double dz = sameWorld ? now.getZ() - before.getZ() : 0;
            double distance = dx * dx + dz * dz;
            boolean walking = !player.isInsideVehicle() && distance >= MINIMUM_DISTANCE_SQUARED
                    && distance <= MAXIMUM_DISTANCE_SQUARED;
            if (host.tick() < externallyMovedUntil.getOrDefault(id, 0L)) {
                var input = player.getCurrentInput();
                walking &= input.isForward() || input.isBackward() || input.isLeft() || input.isRight();
            }
            if (walking) { lastMotion.put(id, host.tick()); }
            moving.put(id, walking || (lastMotion.containsKey(id)
                    && host.tick() - lastMotion.get(id) < STOP_AFTER_TICKS));
        }
        game.stateSnapshot(SyncAction.SNEAK, sneaking);
        game.stateSnapshot(SyncAction.SPRINT, sprinting);
        game.movementSnapshot(moving);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleported(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!game.running() || !game.participants().contains(id)) { return; }
        previous.remove(id);
        lastMotion.remove(id);
        externallyMovedUntil.put(id, host.tick() + 3);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pushed(PlayerVelocityEvent event) {
        if (!game.running() || !game.participants().contains(event.getPlayer().getUniqueId())) { return; }
        externallyMovedUntil.put(event.getPlayer().getUniqueId(), host.tick() + 15);
    }

    interface Host {
        Player player(UUID id);
        long tick();
        BukkitTask repeat(Runnable callback);
    }
}
