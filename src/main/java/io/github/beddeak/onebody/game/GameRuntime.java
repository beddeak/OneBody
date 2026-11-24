package io.github.beddeak.onebody.game;

import java.util.Set;
import java.util.UUID;

/** The small server boundary used by the game and deterministic tick/lifecycle tests. */
public interface GameRuntime {
    interface Task {
        void cancel();
    }

    long currentTick();
    Set<UUID> onlinePlayers();
    MovementInput movementInput(UUID player);
    boolean eligible(UUID player);
    Task later(Runnable callback, long delayTicks);
    Task repeat(Runnable callback, long delayTicks, long intervalTicks);
    void damage(UUID player, double amount);
    void started(Set<UUID> players);
    void stopped(Set<UUID> players);
    void outcome(Set<UUID> players, boolean success);
    void punishmentBar(ActiveSyncChallenge challenge);
    void clearBars(Set<UUID> players);
}
