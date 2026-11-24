package io.github.beddeak.onebody.game;

import io.github.beddeak.onebody.config.GameSettings;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

public final class SyncChallengeManager {
    private final GameRuntime runtime;
    private final DamageManager damageManager;
    private ActiveSyncChallenge active;
    private GameRuntime.Task deadlineTask;
    private GameRuntime.Task actionBarTask;

    public SyncChallengeManager(GameRuntime runtime) {
        this.runtime = runtime;
        damageManager = new DamageManager(runtime);
    }

    public ActiveSyncChallenge active() { return active; }

    public void action(UUID player, SyncAction action, Set<UUID> players, GameSettings settings) {
        if (action.stateful()) {
            return; // Ongoing states require a complete observed frame, not a one-shot response.
        }
        if (active == null) {
            if (players.size() < 2 || !players.contains(player) || !settings.enabledActions().contains(action)) {
                return;
            }
            ActiveSyncChallenge challenge = new ActiveSyncChallenge(action, player, runtime.currentTick(), players, settings, null);
            active = challenge;
            deadlineTask = runtime.later(() -> deadline(challenge), settings.toleranceTicks());
            // No challenge-start message, title or action bar.
            return;
        }
        ActiveSyncChallenge challenge = active;
        if (challenge.action() != action || !challenge.pendingPlayers().contains(player)) {
            return;
        }
        // Check the absolute boundary even if an event runs before that tick's timeout task.
        if (challenge.state() == ChallengeState.SYNCING && runtime.currentTick() >= challenge.deadlineTick()) {
            punish(challenge);
        }
        if (active != challenge) {
            return;
        }
        challenge.complete(player);
        if (challenge.pendingPlayers().isEmpty()) {
            finish(challenge, ChallengeState.COMPLETED);
        }
    }

    public void movement(UUID player, MovementInput input, Set<UUID> players, GameSettings settings,
                         Map<UUID, MovementInput> currentInputs) {
        Map<UUID, Boolean> states = new java.util.LinkedHashMap<>();
        currentInputs.forEach((id, value) -> states.put(id, value.moving()));
        state(player, SyncAction.MOVE, input.moving(), players, settings, states);
    }

    public void state(UUID player, SyncAction action, boolean enabled, Set<UUID> players, GameSettings settings,
                      Map<UUID, Boolean> currentStates) {
        if (!action.stateful()) { return; }
        if (active == null) {
            if (players.size() < 2 || !players.contains(player) || !settings.enabledActions().contains(action)) {
                return;
            }
            MovementInput input = action == SyncAction.MOVE ? (enabled ? MovementInput.MOVING : MovementInput.NONE) : null;
            ActiveSyncChallenge challenge = new ActiveSyncChallenge(action, player,
                    runtime.currentTick(), players, settings, input);
            active = challenge;
            for (UUID id : players) {
                if (enabled == Boolean.TRUE.equals(currentStates.get(id))) {
                    challenge.complete(id);
                }
            }
            if (challenge.pendingPlayers().isEmpty()) {
                finish(challenge, ChallengeState.COMPLETED);
            } else {
                deadlineTask = runtime.later(() -> deadline(challenge), settings.toleranceTicks());
            }
            return;
        }
        ActiveSyncChallenge challenge = active;
        if (challenge.action() != action || !challenge.includes(player)) {
            return;
        }
        if (challenge.state() == ChallengeState.SYNCING && runtime.currentTick() >= challenge.deadlineTick()) {
            punish(challenge);
        }
        if (active != challenge) {
            return;
        }
        Set<UUID> members = challenge.players();
        UUID referencePlayer = members.contains(challenge.initiator()) ? challenge.initiator()
                : members.stream().findFirst().orElse(player);
        boolean reference = Boolean.TRUE.equals(currentStates.get(referencePlayer));
        if (action == SyncAction.MOVE) {
            challenge.movementInput(reference ? MovementInput.MOVING : MovementInput.NONE);
        }
        for (UUID id : members) {
            if (Boolean.TRUE.equals(currentStates.get(id)) == reference) {
                challenge.complete(id);
            } else {
                challenge.reopen(id);
            }
        }
        if (challenge.pendingPlayers().isEmpty()) {
            finish(challenge, ChallengeState.COMPLETED);
        }
    }

    private void deadline(ActiveSyncChallenge challenge) {
        if (active != challenge || challenge.state() != ChallengeState.SYNCING) {
            return;
        }
        long remaining = challenge.deadlineTick() - runtime.currentTick();
        if (remaining > 0) {
            deadlineTask = runtime.later(() -> deadline(challenge), remaining);
            return;
        }
        for (UUID player : challenge.players()) {
            if (!runtime.eligible(player)) {
                remove(player);
            }
        }
        if (active == challenge) {
            punish(challenge);
        }
    }

    private void punish(ActiveSyncChallenge challenge) {
        if (active != challenge || challenge.state() != ChallengeState.SYNCING) {
            return;
        }
        cancelDeadline();
        challenge.state(ChallengeState.PUNISHING);
        runtime.outcome(challenge.players(), false);
        runtime.punishmentBar(challenge);
        // The runtime reads live display settings; damage/tolerance remain the challenge snapshot.
        actionBarTask = runtime.repeat(() -> {
            if (active == challenge) {
                runtime.punishmentBar(challenge);
            }
        }, 1, 10);
        damageManager.start(challenge, () -> active == challenge
                && challenge.state() == ChallengeState.PUNISHING, this::remove);
    }

    public void add(UUID player, MovementInput input) {
        if (active != null && runtime.eligible(player)) {
            active.add(player);
            if (active.action() == SyncAction.MOVE && input.moving() == active.movementInput().moving()) {
                active.complete(player);
            }
        }
    }

    public void remove(UUID player) {
        ActiveSyncChallenge challenge = active;
        if (challenge == null) {
            return;
        }
        challenge.remove(player);
        if (challenge.players().size() < 2) {
            finish(challenge, ChallengeState.CANCELLED);
        } else if (challenge.pendingPlayers().isEmpty()) {
            finish(challenge, ChallengeState.COMPLETED);
        }
    }

    public void cancel() {
        if (active != null) {
            finish(active, ChallengeState.CANCELLED);
        } else {
            cancelTasks();
        }
    }

    private void finish(ActiveSyncChallenge challenge, ChallengeState state) {
        challenge.state(state);
        active = null;
        cancelTasks();
        runtime.clearBars(challenge.players());
        if (state == ChallengeState.COMPLETED) {
            runtime.outcome(challenge.players(), true);
        }
    }

    private void cancelDeadline() {
        if (deadlineTask != null) {
            deadlineTask.cancel();
            deadlineTask = null;
        }
    }

    private void cancelTasks() {
        cancelDeadline();
        damageManager.stop();
        if (actionBarTask != null) {
            actionBarTask.cancel();
            actionBarTask = null;
        }
    }
}
