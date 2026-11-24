package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.config.GameSettings;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.GameRuntime;
import io.github.beddeak.onebody.game.MovementInput;
import io.github.beddeak.onebody.game.SyncAction;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PostureIntegrationTest {
    @ParameterizedTest @EnumSource(value = SyncAction.class, names = {"SNEAK", "SPRINT"})
    void actualHeldPlayerStateClearsFailureWithoutTogglePacketsAndReleaseAlsoRecovers(SyncAction action) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Player first = mock(Player.class), second = mock(Player.class);
        GameRuntime runtime = mock(GameRuntime.class);
        when(runtime.onlinePlayers()).thenReturn(Set.of(a, b));
        when(runtime.eligible(any())).thenReturn(true);
        when(runtime.movementInput(any())).thenReturn(MovementInput.NONE);
        when(runtime.currentTick()).thenReturn(100L);
        when(runtime.later(any(), anyLong())).thenReturn(mock(GameRuntime.Task.class));
        when(runtime.repeat(any(), anyLong(), anyLong())).thenReturn(mock(GameRuntime.Task.class));
        when(first.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        when(second.getLocation()).thenReturn(new Location(null, 0, 64, 0));
        setState(first, action, true); // Already held before the game starts.
        GameManager game = new GameManager(runtime, new GameSettings(2, 3.0, 20, EnumSet.allOf(SyncAction.class)));
        assertTrue(game.start());
        MovementListener listener = new MovementListener(game, new MovementListener.Host() {
            public Player player(UUID id) { return id.equals(a) ? first : second; }
            public long tick() { return runtime.currentTick(); }
            public BukkitTask repeat(Runnable callback) { return mock(BukkitTask.class); }
        });
        listener.start();
        listener.sample();
        assertEquals(action, game.activeChallenge().action());
        when(runtime.currentTick()).thenReturn(103L);
        listener.sample();
        assertEquals(io.github.beddeak.onebody.game.ChallengeState.PUNISHING, game.activeChallenge().state());
        setState(second, action, true);
        listener.sample();
        assertNull(game.activeChallenge());
        // Continuous holding does not create repeated challenges/success messages.
        listener.sample();
        verify(runtime, times(1)).outcome(anySet(), eq(true));
        // Simulates one server-side sprint cancellation (e.g. insufficient hunger).
        setState(first, action, false);
        listener.sample();
        assertNotNull(game.activeChallenge());
        setState(second, action, false);
        listener.sample();
        assertNull(game.activeChallenge());
        game.stop();
        listener.stop();
    }

    private void setState(Player player, SyncAction action, boolean enabled) {
        if (action == SyncAction.SPRINT) { when(player.isSprinting()).thenReturn(enabled); }
        else { when(player.isSneaking()).thenReturn(enabled); }
    }
}
