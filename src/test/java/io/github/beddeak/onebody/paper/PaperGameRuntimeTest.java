package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.config.ConfigManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class PaperGameRuntimeTest {
    private final DamageEventGuard guard = new DamageEventGuard();
    private final PaperGameRuntime runtime = new PaperGameRuntime(mock(JavaPlugin.class), mock(ConfigManager.class), guard);

    @Test void sharingStartupCommitsOnlyAfterSuccessfulStartupAndStopsNormally() {
        List<String> calls = new ArrayList<>();
        runtime.sharingLifecycle(() -> calls.add("start"), () -> calls.add("stop"),
                () -> calls.add("rollback"), () -> calls.add("commit"));
        runtime.started(Set.of());
        runtime.stopped(Set.of());
        assertEquals(List.of("start", "commit", "stop"), calls);
    }

    @Test void sharingInitializationFailureUsesRollbackWithoutCommit() {
        List<String> calls = new ArrayList<>();
        runtime.sharingLifecycle(() -> { calls.add("start"); throw new IllegalStateException("backup failed"); },
                () -> calls.add("stop"), () -> calls.add("rollback"), () -> calls.add("commit"));
        assertThrows(IllegalStateException.class, () -> runtime.started(Set.of()));
        assertEquals(List.of("start", "rollback"), calls);
    }

    @Test void realPenaltyDamageCallIsGuardedAndExceptionCannotLeakTheGuard() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getHealth()).thenReturn(20.0);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getNoDamageTicks()).thenReturn(8);
        doAnswer(call -> {
            assertTrue(guard.active());
            throw new IllegalStateException("damage hook failed");
        }).when(player).damage(1.5);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            assertThrows(IllegalStateException.class, () -> runtime.damage(id, 1.5));
        }
        assertFalse(guard.active());
        verify(player).setNoDamageTicks(0);
        verify(player).setNoDamageTicks(8);
    }
}
