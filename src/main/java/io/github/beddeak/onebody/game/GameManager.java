package io.github.beddeak.onebody.game;

import io.github.beddeak.onebody.config.GameSettings;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameManager {
    private final GameRuntime runtime;
    private final SyncChallengeManager challenges;
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final Map<UUID, EnumMap<SyncAction, Long>> lastActions = new HashMap<>();
    private final Map<UUID, MovementInput> movementInputs = new HashMap<>();
    private GameSettings settings;
    private boolean running;

    public GameManager(GameRuntime runtime, GameSettings settings) {
        this.runtime = runtime;
        this.settings = settings;
        challenges = new SyncChallengeManager(runtime);
    }

    public boolean playerConnected(UUID player) {
        if (!participants.add(player)) {
            return false;
        }
        MovementInput input = runtime.movementInput(player).normalized();
        movementInputs.put(player, input);
        challenges.add(player, input);
        return true;
    }

    public boolean playerDisconnected(UUID player) {
        if (!participants.remove(player)) {
            return false;
        }
        lastActions.remove(player);
        movementInputs.remove(player);
        runtime.clearBars(Set.of(player));
        if (running && participants.size() < 2) {
            stop();
        } else {
            challenges.remove(player);
        }
        return true;
    }

    public void unavailable(UUID player) {
        lastActions.remove(player);
        movementInputs.remove(player);
        runtime.clearBars(Set.of(player));
        challenges.remove(player);
    }

    public boolean start() {
        synchronizeParticipants();
        if (eligibleParticipants().size() < 2) {
            return false;
        }
        reset();
        for (UUID player : participants) {
            movementInputs.put(player, runtime.movementInput(player).normalized());
        }
        running = true;
        try {
            runtime.started(Set.copyOf(participants));
        } catch (RuntimeException exception) {
            stop();
            throw exception;
        }
        return true;
    }

    public void synchronizeParticipants() {
        Set<UUID> online = runtime.onlinePlayers();
        for (UUID player : Set.copyOf(participants)) {
            if (!online.contains(player)) {
                playerDisconnected(player);
            }
        }
        for (UUID player : online) {
            playerConnected(player);
        }
    }

    public void stop() {
        boolean wasRunning = running;
        running = false;
        reset();
        runtime.clearBars(Set.copyOf(participants));
        if (wasRunning) {
            runtime.stopped(Set.copyOf(participants));
        }
    }

    public void shutdown() {
        stop();
        participants.clear();
        lastActions.clear();
    }

    private void reset() {
        challenges.cancel();
        lastActions.clear();
        movementInputs.clear();
    }

    public void action(UUID player, SyncAction action) {
        if (!running || !participants.contains(player) || !runtime.eligible(player)) {
            return;
        }
        long tick = runtime.currentTick();
        EnumMap<SyncAction, Long> previous = lastActions.computeIfAbsent(player, id -> new EnumMap<>(SyncAction.class));
        if (Long.valueOf(tick).equals(previous.put(action, tick))) {
            return; // Consume crossbow multishot / duplicate callbacks once per player/action/tick.
        }
        challenges.action(player, action, eligibleParticipants(), settings);
    }

    public void movement(UUID player, MovementInput input) {
        if (!participants.contains(player) || !runtime.eligible(player)) {
            return;
        }
        input = input.normalized();
        MovementInput previous = movementInputs.put(player, input);
        if (!running || input.equals(previous == null ? MovementInput.NONE : previous)) {
            return; // Jump/sneak-only input packets and held keys are not a new movement action.
        }
        challenges.movement(player, input, eligibleParticipants(), settings, movementInputs);
    }

    /** Store the whole observed frame before checking equality; iteration order cannot invent a mismatch. */
    public void movementSnapshot(Map<UUID, Boolean> moving) {
        if (!running) { return; }
        Set<UUID> eligible = eligibleParticipants();
        for (UUID player : eligible) {
            movementInputs.put(player, Boolean.TRUE.equals(moving.get(player)) ? MovementInput.MOVING : MovementInput.NONE);
        }
        stateSnapshot(SyncAction.MOVE, moving);
    }

    /** Compare a complete current-state frame; either unanimous state resolves the challenge. */
    public void stateSnapshot(SyncAction action, Map<UUID, Boolean> states) {
        if (!running || !action.stateful()) { return; }
        Set<UUID> eligible = eligibleParticipants();
        if (!states.keySet().containsAll(eligible)) { return; }
        ActiveSyncChallenge active = challenges.active();
        if (active != null) {
            if (active.action() == action) {
                UUID player = active.players().stream().filter(eligible::contains).findFirst().orElse(null);
                if (player != null) {
                    challenges.state(player, action, Boolean.TRUE.equals(states.get(player)), eligible, settings, states);
                }
            }
            return;
        }
        if (eligible.size() < 2 || !settings.enabledActions().contains(action)) { return; }
        UUID enabled = eligible.stream().filter(id -> Boolean.TRUE.equals(states.get(id))).findFirst().orElse(null);
        if (enabled != null && eligible.stream().anyMatch(id -> !Boolean.TRUE.equals(states.get(id)))) {
            challenges.state(enabled, action, true, eligible, settings, states);
        }
    }

    /** Chooses one action when a single event contains both attack and injury candidates. */
    public boolean canAcceptAction(UUID player, SyncAction action) {
        if (!running || !participants.contains(player) || !runtime.eligible(player)) {
            return false;
        }
        ActiveSyncChallenge challenge = challenges.active();
        return challenge == null ? settings.enabledActions().contains(action) : challenge.action() == action;
    }

    public Set<UUID> eligibleParticipants() {
        Set<UUID> players = new LinkedHashSet<>();
        for (UUID player : participants) {
            if (runtime.eligible(player)) {
                players.add(player);
            }
        }
        return players;
    }

    public Set<UUID> participants() { return Collections.unmodifiableSet(participants); }
    public boolean running() { return running; }
    public ActiveSyncChallenge activeChallenge() { return challenges.active(); }
    public void settings(GameSettings settings) { this.settings = settings; }
}
