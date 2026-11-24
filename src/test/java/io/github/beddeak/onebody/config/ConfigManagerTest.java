package io.github.beddeak.onebody.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigManagerTest {
    @TempDir Path directory;
    private String defaults;
    private ConfigManager config;

    @BeforeEach void setup() throws Exception {
        try (InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"))) {
            defaults = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        Files.writeString(directory.resolve("config.yml"), defaults);
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("config.yml")).thenAnswer(call -> getClass().getResourceAsStream("/config.yml"));
        config = new ConfigManager(plugin);
        config.reload();
    }

    @Test void defaultRulesAndPrivateDisplaySettingsMatchSpecification() {
        assertEquals(2, config.settings().toleranceTicks());
        assertEquals(3.0, config.settings().damageAmount());
        assertEquals(20, config.settings().damageIntervalTicks());
        assertEquals(14, config.settings().enabledActions().size());
        assertTrue(config.flag("sharing.health"));
        assertTrue(config.flag("sharing.hunger"));
        assertTrue(config.flag("sharing.inventory"));
        assertTrue(config.flag("actionbar.enabled"));
        assertFalse(config.flag("actionbar.reveal-action"));
        assertFalse(config.flag("actionbar.reveal-players"));
    }

    @Test void startupIncludesKoreanContentNameAndAuthorAndTickDurations() {
        var title = config.startupTitle();
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("일심동체", plain.serialize(title.title()));
        assertEquals("mod by beddeak(침댁)", plain.serialize(title.subtitle()));
        assertEquals(Duration.ofMillis(500), title.times().fadeIn());
        assertEquals(Duration.ofSeconds(2), title.times().stay());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sync-tolerance-ticks: 0", "sync-tolerance-ticks: 2.5",
            "damage:\n  amount-per-tick: .NaN", "damage:\n  amount-per-tick: -1",
            "damage:\n  interval-ticks: 0", "actions:\n  jump: 'true'",
            "actionbar:\n  enabled: 'false'", "messages:\n  prefix: 123", "damage: false",
            "sharing: false", "sharing:\n  health: 'true'"})
    void invalidReloadKeepsPreviousSettingsAndMessages(String yaml) throws Exception {
        GameSettings previous = config.settings();
        var previousMessage = config.prefixed("game-started");
        Files.writeString(directory.resolve("config.yml"), yaml);
        assertThrows(IllegalArgumentException.class, config::reload);
        assertSame(previous, config.settings());
        assertEquals(previousMessage, config.prefixed("game-started"));
    }

    @Test void malformedYamlRetainsPreviousConfiguration() throws Exception {
        GameSettings previous = config.settings();
        Files.writeString(directory.resolve("config.yml"), "actions: [unclosed");
        assertThrows(org.bukkit.configuration.InvalidConfigurationException.class, config::reload);
        assertSame(previous, config.settings());
    }

    @Test void validReloadUpdatesGameDisplayAndMessageOptions() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                config-version: 6
                sync-tolerance-ticks: 3
                damage:
                  amount-per-tick: 2.0
                  interval-ticks: 4
                actions:
                  jump: false
                actionbar:
                  enabled: false
                messages:
                  game-started: '&atest'
                  success-enabled: false
                """);
        config.reload();
        assertEquals(3, config.settings().toleranceTicks());
        assertEquals(2, config.settings().damageAmount());
        assertEquals(4, config.settings().damageIntervalTicks());
        assertFalse(config.settings().enabledActions().contains(io.github.beddeak.onebody.game.SyncAction.JUMP));
        assertFalse(config.flag("actionbar.enabled"));
        assertFalse(config.flag("actionbar.reveal-action"));
        assertFalse(config.flag("messages.success-enabled"));
        assertEquals("test", PlainTextComponentSerializer.plainText().serialize(config.message("game-started")));
    }

    @ParameterizedTest @ValueSource(ints = {1, 20, 60})
    void legacyStockPenaltyMigratesToThreePerSecondWithOriginalBackedUp(int oldInterval) throws Exception {
        String original = defaults.replace("config-version: 7\n", "")
                .replace("amount-per-tick: 3.0", "amount-per-tick: 1.0")
                .replace("interval-ticks: 20", "interval-ticks: " + oldInterval)
                .replace("  game-started:", "  joined: '&aold'\n  game-started:");
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(20, config.settings().damageIntervalTicks());
        assertEquals(3.0, config.settings().damageAmount());
        assertEquals(original, Files.readString(directory.resolve("config-v1-backup.yml")));
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(7, saved.getInt("config-version"));
        assertEquals(20, saved.getInt("damage.interval-ticks"));
        assertNull(saved.get("messages.joined"));
    }

    @Test void explicitNewVersionCustomDamageIsNotOverwrittenByReload() throws Exception {
        Files.writeString(directory.resolve("config.yml"), defaults.replace("interval-ticks: 20", "interval-ticks: 100")
                .replace("amount-per-tick: 3.0", "amount-per-tick: 2.0"));
        config.reload();
        config.reload();
        assertEquals(100, config.settings().damageIntervalTicks());
        assertEquals(2.0, config.settings().damageAmount());
        assertFalse(Files.exists(directory.resolve("config-v1-backup.yml")));
    }

    @Test void legacyCustomizedDamageAndDisplaySettingsSurviveMigration() throws Exception {
        String original = defaults.replace("config-version: 7\n", "")
                .replace("amount-per-tick: 3.0", "amount-per-tick: 0.5")
                .replace("interval-ticks: 20", "interval-ticks: 100")
                .replace("reveal-action: false", "reveal-action: true");
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(100, config.settings().damageIntervalTicks());
        assertEquals(0.5, config.settings().damageAmount());
        assertFalse(config.flag("actionbar.reveal-action"));
        assertTrue(config.settings().enabledActions().contains(io.github.beddeak.onebody.game.SyncAction.MOVE));
    }

    @Test void versionTwoConfigIsBackedUpBeforeAddingNewDefaultsAndPenalty() throws Exception {
        String oldConfig = """
                config-version: 2
                damage:
                  amount-per-tick: 1.0
                  interval-ticks: 60
                """;
        Files.writeString(directory.resolve("config.yml"), oldConfig);
        config.reload();
        assertEquals(oldConfig, Files.readString(directory.resolve("config-v2-backup.yml")));
        assertEquals(3.0, config.settings().damageAmount());
        assertEquals(20, config.settings().damageIntervalTicks());
        assertTrue(config.flag("sharing.inventory"));
        assertEquals(14, config.settings().enabledActions().size());
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertTrue(saved.getBoolean("sharing.inventory"));
        assertTrue(saved.getBoolean("actions.take-damage"));
        assertTrue(saved.getBoolean("actions.fall-damage"));
    }

    @Test void versionFourRemovesEatingRulesAndEnablesRequiredActionsWithoutChangingHunger() throws Exception {
        String original = """
                config-version: 4
                damage:
                  amount-per-tick: 2.0
                  interval-ticks: 40
                sharing:
                  hunger: true
                actions:
                  eat: true
                  jump: false
                actionbar:
                  enabled: false
                  reveal-action: false
                messages:
                  action-hints:
                    eat: '&eold food hint'
                    jump: '&6custom jump hint'
                """;
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(original, Files.readString(directory.resolve("config-v4-backup.yml")));
        assertTrue(config.flag("sharing.hunger"));
        assertTrue(config.flag("actionbar.enabled"));
        assertFalse(config.flag("actionbar.reveal-action"));
        assertEquals(2.0, config.settings().damageAmount());
        assertEquals(40, config.settings().damageIntervalTicks());
        assertEquals(13, config.settings().enabledActions().size());
        assertFalse(config.settings().enabledActions().contains(io.github.beddeak.onebody.game.SyncAction.JUMP));
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(7, saved.getInt("config-version"));
        assertNull(saved.get("actions.eat"));
        assertNull(saved.get("messages.action-hints.eat"));
        assertNull(saved.get("messages.action-hints"));
        config.reload();
        assertTrue(config.flag("actionbar.enabled"));
        assertEquals(original, Files.readString(directory.resolve("config-v4-backup.yml")));
    }

    @ParameterizedTest @ValueSource(ints = {3, 4, 5})
    void previousStockPenaltyBecomesOneAndAHalfHeartsAndGuidanceIsVisible(int oldVersion) throws Exception {
        String original = """
                config-version: %d
                damage:
                  amount-per-tick: 1.5
                  interval-ticks: 20
                actionbar:
                  enabled: false
                  reveal-action: false
                messages:
                  punishment: '&c일심동체를 맞춰라!'
                """.formatted(oldVersion);
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(3.0, config.settings().damageAmount());
        assertEquals(20, config.settings().damageIntervalTicks());
        assertTrue(config.flag("actionbar.enabled"));
        assertEquals(original, Files.readString(directory.resolve("config-v" + oldVersion + "-backup.yml")));
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(7, saved.getInt("config-version"));
        assertNull(saved.get("actionbar.reveal-action"));
        assertNull(saved.get("messages.punishment"));
        assertEquals("&f&l스프린트 &f(모두 달리기 또는 모두 해제)", saved.getString("messages.action-names.sprint"));
        // A later deliberate customization in the new schema must not be migrated again.
        saved.set("damage.amount-per-tick", 1.5);
        saved.set("actionbar.enabled", false);
        saved.save(directory.resolve("config.yml").toFile());
        config.reload();
        assertEquals(1.5, config.settings().damageAmount());
        assertFalse(config.flag("actionbar.enabled"));
    }

    @Test void versionSixAddsInteractionsAndCurrentStateHintsPreservingCustomizedRules() throws Exception {
        String original = """
                config-version: 6
                damage:
                  amount-per-tick: 1.5
                  interval-ticks: 20
                actionbar:
                  enabled: false
                actions:
                  take-damage: true
                  fall-damage: true
                messages:
                  action-names:
                    sprint: '&f&l스프린트'
                    sneak: '&6나만의 웅크리기'
                """;
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(original, Files.readString(directory.resolve("config-v6-backup.yml")));
        assertEquals(1.5, config.settings().damageAmount());
        assertFalse(config.flag("actionbar.enabled"));
        var plain = PlainTextComponentSerializer.plainText();
        assertEquals("스프린트 (모두 달리기 또는 모두 해제)", plain.serialize(config.actionName(io.github.beddeak.onebody.game.SyncAction.SPRINT)));
        assertEquals("나만의 웅크리기", plain.serialize(config.actionName(io.github.beddeak.onebody.game.SyncAction.SNEAK)));
        assertEquals(14, config.settings().enabledActions().size());
        config.reload();
        assertEquals(original, Files.readString(directory.resolve("config-v6-backup.yml")));
    }

    @Test void hiddenGuidanceInVersionThreeIsEnabledOnceWithoutChangingCustomDamage() throws Exception {
        String original = """
                config-version: 3
                damage:
                  amount-per-tick: 1.0
                  interval-ticks: 20
                actionbar:
                  enabled: false
                  reveal-action: false
                  reveal-players: false
                messages:
                  punishment: '&c共有カスタム'
                """;
        Files.writeString(directory.resolve("config.yml"), original);
        config.reload();
        assertEquals(original, Files.readString(directory.resolve("config-v3-backup.yml")));
        assertTrue(config.flag("actionbar.enabled"));
        assertFalse(config.flag("actionbar.reveal-action"));
        assertFalse(config.flag("actionbar.reveal-players"));
        assertEquals(1.0, config.settings().damageAmount());
        assertEquals(20, config.settings().damageIntervalTicks());
        var saved = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        assertEquals(7, saved.getInt("config-version"));
        assertNull(saved.get("actionbar.reveal-action"));
        assertEquals("&f&l점프", saved.getString("messages.action-names.jump"));
        assertNull(saved.get("messages.punishment"));
    }
}
