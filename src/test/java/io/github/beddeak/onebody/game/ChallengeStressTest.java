package io.github.beddeak.onebody.game;

import static org.junit.jupiter.api.Assertions.*;

import io.github.beddeak.onebody.config.GameSettings;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChallengeStressTest {
    private final List<UUID> players = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4));

    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"MOVE", "SNEAK", "SPRINT"})
    void everyFourPlayerStateTransitionResolvesExactlyWhenCurrentlyUnanimous(SyncAction action) {
        for (int before = 0; before < 16; before++) {
            for (int after = 0; after < 16; after++) {
                FakeRuntime runtime = runtime();
                GameManager game = game(runtime);
                game.stateSnapshot(action, frame(before));
                runtime.advanceTo(103); // Includes recovery after entering punishment.
                game.stateSnapshot(action, frame(after));
                if (after == 0 || after == 15) {
                    assertNull(game.activeChallenge(), action + " " + before + "->" + after);
                    assertEquals(0, runtime.activeTasks());
                } else {
                    assertNotNull(game.activeChallenge(), action + " " + before + "->" + after);
                    assertEquals(action, game.activeChallenge().action());
                    assertFalse(game.activeChallenge().pendingPlayers().isEmpty());
                }
                game.stop();
                runtime.advanceTo(200);
                assertEquals(0, runtime.totalHits(), "stopped/recovered challenges must not damage later");
                assertEquals(0, runtime.activeTasks());
            }
        }
    }

    @ParameterizedTest @ValueSource(longs = {7, 26, 129, 20261010})
    void randomizedActionsDeathReconnectReloadAndRestartsKeepInvariants(long seed) {
        FakeRuntime runtime = runtime();
        GameManager game = game(runtime);
        Random random = new Random(seed);
        for (int step = 0; step < 2500; step++) {
            UUID id = players.get(random.nextInt(players.size()));
            SyncAction action = SyncAction.values()[random.nextInt(SyncAction.values().length)];
            switch (random.nextInt(9)) {
                case 0 -> game.action(id, action);
                case 1 -> game.stateSnapshot(action, frame(random.nextInt(16)));
                case 2 -> runtime.advanceTo(runtime.tick + random.nextInt(5));
                case 3 -> { runtime.available.remove(id); game.unavailable(id); }
                case 4 -> { runtime.available.add(id); }
                case 5 -> {
                    runtime.online.remove(id);
                    runtime.available.remove(id);
                    game.playerDisconnected(id);
                }
                case 6 -> {
                    runtime.online.add(id);
                    runtime.available.add(id);
                    game.playerConnected(id);
                }
                case 7 -> { game.stop(); game.start(); }
                case 8 -> game.settings(new GameSettings(2, 3, 20,
                        random.nextBoolean() ? EnumSet.allOf(SyncAction.class) : Set.of(action)));
            }
            ActiveSyncChallenge active = game.activeChallenge();
            if (active == null) { assertEquals(0, runtime.activeTasks(), "seed=" + seed + " step=" + step); }
            else {
                assertTrue(game.running());
                assertTrue(active.players().size() >= 2);
                assertTrue(game.participants().containsAll(active.players()));
                assertTrue(java.util.Collections.disjoint(active.completedPlayers(), active.pendingPlayers()));
                assertFalse(active.pendingPlayers().isEmpty());
                assertTrue(runtime.activeTasks() <= 2, "only deadline OR bar+damage tasks");
            }
        }
        game.shutdown();
        int hits = runtime.totalHits();
        runtime.advanceTo(runtime.tick + 100);
        assertEquals(hits, runtime.totalHits());
        assertEquals(0, runtime.activeTasks());
    }

    private Map<UUID, Boolean> frame(int mask) {
        Map<UUID, Boolean> frame = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) { frame.put(players.get(i), (mask & (1 << i)) != 0); }
        return frame;
    }

    private FakeRuntime runtime() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.available.addAll(players);
        runtime.online.addAll(players);
        return runtime;
    }

    private GameManager game(FakeRuntime runtime) {
        GameManager game = new GameManager(runtime, new GameSettings(2, 3, 20, EnumSet.allOf(SyncAction.class)));
        assertTrue(game.start());
        return game;
    }
}
