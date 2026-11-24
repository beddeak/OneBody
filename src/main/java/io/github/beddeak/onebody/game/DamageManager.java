package io.github.beddeak.onebody.game;

import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class DamageManager {
    private final GameRuntime runtime;
    private GameRuntime.Task task;

    public DamageManager(GameRuntime runtime) {
        this.runtime = runtime;
    }

    public void start(ActiveSyncChallenge challenge, BooleanSupplier stillCurrent,
                      java.util.function.Consumer<UUID> unavailable) {
        stop();
        Runnable pulse = () -> {
            // damage() may synchronously cause death, leave, stop or plugin disable.
            for (UUID player : challenge.players()) {
                if (!stillCurrent.getAsBoolean()) {
                    return;
                }
                if (!challenge.includes(player)) {
                    continue; // A previous target's damage event may have removed this player.
                }
                if (!runtime.eligible(player)) {
                    unavailable.accept(player);
                    continue;
                }
                runtime.damage(player, challenge.settings().damageAmount());
                if (stillCurrent.getAsBoolean() && !runtime.eligible(player)) {
                    unavailable.accept(player);
                }
            }
        };
        int interval = challenge.settings().damageIntervalTicks();
        task = runtime.repeat(pulse, interval, interval);
        // The first hit waits a full interval. Quickly recovered challenges cause no health damage.
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
