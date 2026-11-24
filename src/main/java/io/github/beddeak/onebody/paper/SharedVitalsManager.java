package io.github.beddeak.onebody.paper;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.beddeak.onebody.game.GameManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Shares applied health and hunger on the main thread, without generating synthetic damage events. */
public final class SharedVitalsManager implements Listener {
    private static final double EPSILON = 0.000001;
    private final JavaPlugin plugin;
    private final GameManager game;
    private final Predicate<Player> penaltyInProgress;
    private final Map<UUID, Observation> observations = new LinkedHashMap<>();
    private final Map<UUID, StartupVitals> startup = new LinkedHashMap<>();
    private BukkitTask task;
    private boolean active;
    private boolean applying;
    private boolean shareHealth;
    private boolean shareHunger;
    private boolean groupDeathPending;
    private double health;
    private Food food;
    private UUID foodSource;
    private int foodPriority;

    public SharedVitalsManager(JavaPlugin plugin, GameManager game, Predicate<Player> penaltyInProgress) {
        this.plugin = plugin;
        this.game = game;
        this.penaltyInProgress = penaltyInProgress;
    }

    /** Call after GameManager.start(); the selected living player's values initialize the group. */
    public void start(UUID leader) {
        start(leader, true, true);
    }

    public void start(UUID leader, boolean shareHealth, boolean shareHunger) {
        stop();
        this.shareHealth = shareHealth;
        this.shareHunger = shareHunger;
        if (!game.running() || (!shareHealth && !shareHunger)) { return; }
        Player source = player(leader);
        if (!living(source) || !game.participants().contains(leader)) {
            source = game.participants().stream().map(this::player).filter(this::living).findFirst().orElse(null);
        }
        if (source == null) { return; }
        for (UUID id : game.participants()) {
            Player participant = player(id);
            if (living(participant)) {
                startup.put(id, new StartupVitals(participant.getHealth(), new Food(participant.getFoodLevel(),
                        participant.getSaturation(), participant.getExhaustion())));
            }
        }
        try {
            active = true;
            seed(source);
            applyAll();
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::synchronize, 1L, 1L);
        } catch (RuntimeException | Error failure) {
            rollbackStart();
            throw failure;
        }
    }

    /** Called after the complete game startup (including inventory and messages) succeeds. */
    public void commitStart() { startup.clear(); }

    /** Roll back only an uncommitted failed startup; normal game stop never restores vitals. */
    public void rollbackStart() {
        Map<UUID, StartupVitals> previous = new LinkedHashMap<>(startup);
        stop();
        applying = true;
        try {
            for (Map.Entry<UUID, StartupVitals> entry : previous.entrySet()) {
                Player player = player(entry.getKey());
                if (!living(player)) { continue; }
                StartupVitals snapshot = entry.getValue();
                if (shareHealth) { player.setHealth(Math.min(snapshot.health, maxHealth(player))); }
                if (shareHunger) {
                    player.setFoodLevel(snapshot.food.level);
                    player.setSaturation(snapshot.food.saturation);
                    player.setExhaustion(snapshot.food.exhaustion);
                }
            }
        } finally {
            applying = false;
        }
    }

    /** New joiners and respawned players receive the current group values, without reviving dead players. */
    public void connected(Player player) {
        if (!enabled() || !participant(player) || !living(player)) { return; }
        if (shareHealth && health <= EPSILON) {
            // A wiped group starts a fresh shared life only after a real vanilla respawn.
            seed(player);
            applyAll();
        } else {
            // Exclude the join/respawn's fresh values from deltas, but flush existing players first.
            observations.remove(player.getUniqueId());
            synchronize();
        }
    }

    public void stop() {
        active = false;
        if (task != null) { task.cancel(); task = null; }
        observations.clear();
        startup.clear();
        groupDeathPending = false;
        foodSource = null;
        foodPriority = 0;
    }

    public void shutdown() { stop(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!shareHealth || event.isCancelled() || event.getFinalDamage() <= 0 || !(event.getEntity() instanceof Player player)
                || !observedParticipant(player)) { return; }
        Observation observation = observe(player);
        // Events run before vanilla applies damage. Classify the next observed actual change instead.
        observation.pending = penaltyInProgress.test(player) ? Change.PENALTY : Change.ORDINARY;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void regain(EntityRegainHealthEvent event) {
        if (!shareHealth || event.isCancelled() || event.getAmount() <= 0 || !(event.getEntity() instanceof Player player)
                || !observedParticipant(player)) { return; }
        Observation observation = observe(player);
        observation.pending = switch (event.getRegainReason()) {
            case SATIATED, REGEN, MAGIC_REGEN -> Change.PASSIVE_REGAIN;
            default -> Change.ORDINARY;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void foodChanged(FoodLevelChangeEvent event) {
        if (!event.isCancelled() && event.getEntity() instanceof Player player) { selectFood(player, 2); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exhausted(EntityExhaustionEvent event) {
        if (!event.isCancelled() && event.getEntity() instanceof Player player) { selectFood(player, 1); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consumed(PlayerItemConsumeEvent event) {
        if (!event.isCancelled()) { selectFood(event.getPlayer(), 3); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void died(PlayerDeathEvent event) {
        if (shareHealth && !event.isCancelled() && observedParticipant(event.getEntity())) {
            observe(event.getEntity());
            groupDeathPending = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) { connected(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawned(PlayerPostRespawnEvent event) { connected(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        // ParticipantListener removes the UUID at MONITOR. Preserve the final applied delta first.
        if (observedParticipant(event.getPlayer())) { synchronize(); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spectator(PlayerGameModeChangeEvent event) {
        if (!event.isCancelled() && event.getNewGameMode() == GameMode.SPECTATOR
                && observedParticipant(event.getPlayer())) { synchronize(); }
    }

    /** Called once per tick after the previous tick's cancellable events have actually applied. */
    void synchronize() {
        if (!active || applying) { return; }
        if (!game.running()) { stop(); return; }
        Set<UUID> participants = Set.copyOf(game.participants());
        observations.keySet().removeIf(id -> !participants.contains(id));
        double ordinary = 0;
        double penalty = 0;
        double passiveRegain = 0;
        double maximum = Double.POSITIVE_INFINITY;
        Player fallbackFood = null;
        Player chosenFood = null;

        for (UUID id : participants) {
            Player player = player(id);
            if (player == null || !player.isOnline() || player.getGameMode() == GameMode.SPECTATOR) {
                observations.remove(id);
                continue;
            }
            Observation observation = observations.get(id);
            if (observation != null) {
                observe(player); // Dead players still contribute their actual final loss to zero.
                ordinary += observation.ordinary;
                penalty = Math.max(penalty, observation.penalty);
                passiveRegain = Math.max(passiveRegain, observation.passiveRegain);
                if (living(player) && !Food.read(player).equals(observation.food)) {
                    if (fallbackFood == null) { fallbackFood = player; }
                    if (id.equals(foodSource)) { chosenFood = player; }
                }
            }
            if (living(player)) { maximum = Math.min(maximum, maxHealth(player)); }
        }

        if (shareHealth) {
            health = Math.max(0, health + ordinary - penalty + passiveRegain);
            if (Double.isFinite(maximum)) { health = Math.min(health, maximum); }
            // A real uncancelled death cannot be undone by someone else's same-tick healing.
            if (groupDeathPending) { health = 0; }
        }
        groupDeathPending = false;
        if (chosenFood == null) { chosenFood = fallbackFood; }
        if (shareHunger && chosenFood != null) { food = Food.read(chosenFood); }
        foodSource = null;
        foodPriority = 0;
        applyAll();
    }

    private Observation observe(Player player) {
        Observation observation = observations.get(player.getUniqueId());
        double current = player.getHealth();
        double delta = current - observation.health;
        if (delta < -EPSILON && observation.pending == Change.PENALTY) {
            observation.penalty -= delta;
        } else if (delta > EPSILON && observation.pending == Change.PASSIVE_REGAIN) {
            observation.passiveRegain += delta;
        } else {
            observation.ordinary += delta;
        }
        observation.health = current;
        observation.pending = Change.ORDINARY;
        return observation;
    }

    private void selectFood(Player player, int priority) {
        if (!shareHunger || !observedParticipant(player) || priority < foodPriority) { return; }
        foodSource = player.getUniqueId();
        foodPriority = priority;
    }

    private void seed(Player player) {
        health = Math.max(0, player.getHealth());
        if (shareHealth) {
            for (UUID id : game.participants()) {
                Player participant = player(id);
                if (living(participant)) { health = Math.min(health, maxHealth(participant)); }
            }
        }
        food = Food.read(player);
        observations.clear();
        groupDeathPending = false;
    }

    private void applyAll() {
        for (UUID id : Set.copyOf(game.participants())) {
            if (!enabled()) { return; }
            Player player = player(id);
            if (living(player)) { apply(player); }
            else if (player != null && observations.containsKey(id)) {
                observations.put(id, new Observation(player.getHealth(), Food.read(player)));
            }
        }
    }

    private void apply(Player player) {
        applying = true;
        try {
            double appliedHealth = Math.min(health, maxHealth(player));
            if (shareHealth && Math.abs(player.getHealth() - appliedHealth) > EPSILON) { player.setHealth(appliedHealth); }
            if (!enabled() || !living(player)) {
                if (enabled()) { observations.put(player.getUniqueId(), new Observation(player.getHealth(), Food.read(player))); }
                return;
            }
            if (shareHunger) {
                if (player.getFoodLevel() != food.level) { player.setFoodLevel(food.level); }
                if (Float.compare(player.getSaturation(), food.saturation) != 0) { player.setSaturation(food.saturation); }
                if (Float.compare(player.getExhaustion(), food.exhaustion) != 0) { player.setExhaustion(food.exhaustion); }
            }
            // Save all baselines after all setters. Replication itself must never become a new loss/gain.
            observations.put(player.getUniqueId(), new Observation(player.getHealth(), Food.read(player)));
        } finally {
            applying = false;
        }
    }

    private boolean observedParticipant(Player player) {
        return enabled() && !applying && participant(player) && observations.containsKey(player.getUniqueId());
    }

    private boolean participant(Player player) { return game.participants().contains(player.getUniqueId()); }
    private boolean enabled() { return active && game.running(); }
    private Player player(UUID id) { return id == null ? null : plugin.getServer().getPlayer(id); }

    private boolean living(Player player) {
        return player != null && player.isOnline() && !player.isDead() && player.getHealth() > 0
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    @SuppressWarnings("deprecation") // Paper's effective maximum includes vanilla attributes and modifiers.
    private double maxHealth(Player player) { return Math.max(EPSILON, player.getMaxHealth()); }

    private enum Change { ORDINARY, PENALTY, PASSIVE_REGAIN }

    private record StartupVitals(double health, Food food) {}

    private static final class Observation {
        double health;
        final Food food;
        Change pending = Change.ORDINARY;
        double ordinary;
        double penalty;
        double passiveRegain;

        Observation(double health, Food food) { this.health = health; this.food = food; }
    }

    private record Food(int level, float saturation, float exhaustion) {
        static Food read(Player player) {
            int level = Math.clamp(player.getFoodLevel(), 0, 20);
            return new Food(level, Math.clamp(player.getSaturation(), 0, level),
                    Math.clamp(player.getExhaustion(), 0, 40));
        }
    }
}
