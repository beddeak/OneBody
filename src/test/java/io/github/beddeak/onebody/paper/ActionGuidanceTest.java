package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.config.ConfigManager;
import io.github.beddeak.onebody.game.ActiveSyncChallenge;
import io.github.beddeak.onebody.game.ChallengeState;
import io.github.beddeak.onebody.game.SyncAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ActionGuidanceTest {
    @TempDir Path directory;
    private ConfigManager config;
    private PaperGameRuntime runtime;
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    private final Player first = mock(Player.class), second = mock(Player.class);
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @BeforeEach void prepare() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            Files.writeString(directory.resolve("config.yml"), new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(call -> getClass().getResourceAsStream("/config.yml"));
        config = new ConfigManager(plugin);
        config.reload();
        runtime = new PaperGameRuntime(plugin, config, new DamageEventGuard());
        when(first.isOnline()).thenReturn(true);
        when(second.isOnline()).thenReturn(true);
    }

    private ActiveSyncChallenge challenge(SyncAction action, ChallengeState state) {
        ActiveSyncChallenge challenge = mock(ActiveSyncChallenge.class);
        when(challenge.action()).thenReturn(action);
        when(challenge.state()).thenReturn(state);
        when(challenge.players()).thenReturn(Set.of(a, b));
        when(challenge.pendingPlayers()).thenReturn(Set.of(b));
        return challenge;
    }

    private void display(ActiveSyncChallenge challenge) {
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(a)).thenReturn(first);
            bukkit.when(() -> Bukkit.getPlayer(b)).thenReturn(second);
            runtime.punishmentBar(challenge);
        }
    }

    @ParameterizedTest @EnumSource(SyncAction.class)
    void failedActionShowsKoreanInstructionToAllWithoutExposingPlayerNames(SyncAction action) {
        display(challenge(action, ChallengeState.PUNISHING));
        var bar = ArgumentCaptor.forClass(Component.class);
        verify(first).sendActionBar(bar.capture());
        verify(second).sendActionBar(bar.getValue());
        String instruction = plain.serialize(config.actionName(action));
        String shown = plain.serialize(bar.getValue());
        assertFalse(instruction.isBlank());
        assertTrue(instruction.matches(".*[가-힣].*"));
        assertEquals("해야 할 행동: " + instruction, shown);
        assertFalse(shown.contains(action.name()));
        verify(first, never()).getName();
        verify(second, never()).getName();
    }

    @ParameterizedTest @EnumSource(value = ChallengeState.class, names = "PUNISHING", mode = EnumSource.Mode.EXCLUDE)
    void noInstructionBeforeFailureOrAfterCompletionAndCancellation(ChallengeState state) {
        display(challenge(SyncAction.JUMP, state));
        verify(first, never()).sendActionBar(any(Component.class));
        verify(second, never()).sendActionBar(any(Component.class));
    }

    @Test void movementHintExplainsBothValidRecoveryChoicesRatherThanAFrozenDirection() {
        String movement = plain.serialize(config.actionName(SyncAction.MOVE));
        assertTrue(movement.contains("모두 이동 또는 모두 정지"));
        assertTrue(movement.contains("방향 자유"));
    }

    @Test void fallAndOrdinaryDamageHaveDistinctUsefulInstructions() {
        assertEquals("피격 (낙하 제외)", plain.serialize(config.actionName(SyncAction.TAKE_DAMAGE)));
        assertEquals("낙하 피해", plain.serialize(config.actionName(SyncAction.FALL_DAMAGE)));
    }

    @Test void guidanceTextReloadsWithoutRestartingTheGame() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: 6
                messages:
                  action-names:
                    jump: '&6다 함께 점프!'
                """);
        config.reload();
        display(challenge(SyncAction.JUMP, ChallengeState.PUNISHING));
        var bar = ArgumentCaptor.forClass(Component.class);
        verify(first).sendActionBar(bar.capture());
        assertTrue(plain.serialize(bar.getValue()).contains("다 함께 점프!"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 4, 5, 6})
    void oldGenericMessageAndHiddenActionOptionCannotHideSprint(int version) throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: %d
                actionbar:
                  reveal-action: false
                messages:
                  punishment: '&c일심동체를 맞춰라!'
                  action-hints:
                    sprint: '&e달리기를 시작하세요!'
                """.formatted(version));
        config.reload();
        display(challenge(SyncAction.SPRINT, ChallengeState.PUNISHING));
        var bar = ArgumentCaptor.forClass(Component.class);
        verify(first).sendActionBar(bar.capture());
        verify(second).sendActionBar(bar.getValue());
        assertEquals("해야 할 행동: 스프린트 (모두 달리기 또는 모두 해제)", plain.serialize(bar.getValue()));
    }

    @Test void upgradedDisabledActionBarDisplaysRequiredActionWithoutManualConfigReset() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: 5
                actionbar:
                  enabled: false
                  reveal-action: false
                """);
        config.reload();
        display(challenge(SyncAction.BLOCK_PLACE, ChallengeState.PUNISHING));
        var bar = ArgumentCaptor.forClass(Component.class);
        verify(first).sendActionBar(bar.capture());
        assertEquals("해야 할 행동: 블록 설치", plain.serialize(bar.getValue()));
    }

    @Test void explicitCurrentVersionOptOutCanDisableTheWholeActionBar() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: 6
                actionbar:
                  enabled: false
                """);
        config.reload();
        display(challenge(SyncAction.JUMP, ChallengeState.PUNISHING));
        verify(first, never()).sendActionBar(any(Component.class));
        verify(second, never()).sendActionBar(any(Component.class));
    }
}
