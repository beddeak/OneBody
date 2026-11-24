package io.github.beddeak.onebody.game;

import io.github.beddeak.onebody.config.GameSettings;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class ActiveSyncChallenge {
    private final SyncAction action;
    private final UUID initiator;
    private final long startTick;
    private final GameSettings settings;
    private MovementInput movementInput;
    private final Set<UUID> completedPlayers = new LinkedHashSet<>();
    private final Set<UUID> pendingPlayers;
    private ChallengeState state = ChallengeState.SYNCING;

    ActiveSyncChallenge(SyncAction action, UUID initiator, long startTick,
                        Set<UUID> players, GameSettings settings, MovementInput movementInput) {
        this.action = action;
        this.initiator = initiator;
        this.startTick = startTick;
        this.settings = settings;
        this.movementInput = movementInput;
        pendingPlayers = new LinkedHashSet<>(players);
        complete(initiator);
    }

    boolean complete(UUID player) {
        if (!pendingPlayers.remove(player)) {
            return false;
        }
        completedPlayers.add(player);
        return true;
    }

    void add(UUID player) {
        if (!completedPlayers.contains(player)) {
            pendingPlayers.add(player);
        }
    }

    void remove(UUID player) {
        pendingPlayers.remove(player);
        completedPlayers.remove(player);
    }

    void reopen(UUID player) {
        if (completedPlayers.remove(player)) {
            pendingPlayers.add(player);
        }
    }

    void movementInput(MovementInput input) { movementInput = input; }

    void state(ChallengeState state) {
        this.state = state;
    }

    public SyncAction action() { return action; }
    public UUID initiator() { return initiator; }
    public long startTick() { return startTick; }
    public long deadlineTick() { return startTick + settings.toleranceTicks(); }
    public GameSettings settings() { return settings; }
    public MovementInput movementInput() { return movementInput; }
    public ChallengeState state() { return state; }
    public Set<UUID> completedPlayers() { return Collections.unmodifiableSet(completedPlayers); }
    public Set<UUID> pendingPlayers() { return Collections.unmodifiableSet(pendingPlayers); }
    public boolean includes(UUID player) { return completedPlayers.contains(player) || pendingPlayers.contains(player); }

    public Set<UUID> players() {
        Set<UUID> players = new LinkedHashSet<>(completedPlayers);
        players.addAll(pendingPlayers);
        return Collections.unmodifiableSet(players);
    }
}
