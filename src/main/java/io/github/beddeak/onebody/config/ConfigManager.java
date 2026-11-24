package io.github.beddeak.onebody.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;
import io.github.beddeak.onebody.game.SyncAction;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigManager {
    private final JavaPlugin plugin;
    private final LegacyComponentSerializer colors = LegacyComponentSerializer.legacyAmpersand();
    private YamlConfiguration config;
    private GameSettings settings;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() throws IOException, InvalidConfigurationException {
        YamlConfiguration next = new YamlConfiguration();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        next.load(configFile);
        Object storedVersion = next.get("config-version");
        int oldVersion = storedVersion instanceof Number number ? number.intValue() : 1;
        boolean legacy = oldVersion < 7;
        try (InputStream input = Objects.requireNonNull(plugin.getResource("config.yml"));
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            next.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        GameSettings.positiveInt(next, "config-version");
        for (String section : new String[]{"damage", "sharing", "actions", "actionbar", "startup", "messages"}) {
            if (!next.isConfigurationSection(section)) {
                throw new IllegalArgumentException(section + "는 YAML 설정 묶음이어야 합니다.");
            }
        }
        GameSettings nextSettings = GameSettings.read(next);
        for (String key : new String[]{"actionbar.enabled", "actionbar.reveal-players",
                "messages.success-enabled", "messages.failed-enabled", "sharing.health", "sharing.hunger", "sharing.inventory"}) {
            GameSettings.requireBoolean(next, key);
        }
        for (String key : new String[]{"startup.fade-in-ticks", "startup.stay-ticks", "startup.fade-out-ticks"}) {
            GameSettings.nonNegativeInt(next, key);
        }
        for (String key : next.getDefaultSection().getKeys(true)) {
            if (next.getDefaultSection().isString(key) && !next.isString(key)) {
                throw new IllegalArgumentException(key + "는 문자열이어야 합니다.");
            }
        }
        if (oldVersion < 6) {
            // Apply the selected stock penalty once; preserve deliberately customized values.
            if ((oldVersion < 3 && nextSettings.damageAmount() == 1.0
                    && (nextSettings.damageIntervalTicks() == 1 || nextSettings.damageIntervalTicks() == 20
                        || nextSettings.damageIntervalTicks() == 60))
                    || (nextSettings.damageAmount() == 1.5 && nextSettings.damageIntervalTicks() == 20)) {
                next.set("damage.amount-per-tick", 3.0);
                next.set("damage.interval-ticks", 20);
                nextSettings = GameSettings.read(next);
            }
            // Required actions must be visible after upgrading from any old hidden-guidance config.
            next.set("actionbar.enabled", true);
            next.set("actionbar.reveal-action", null);
            next.set("messages.punishment", null);
            next.set("messages.action-hints", null);
            next.set("actions.eat", null);
            for (String key : new String[]{"joined", "already-joined", "left", "not-joined", "player-only"}) {
                next.set("messages." + key, null);
            }
        }
        if (legacy) {
            for (String action : new String[]{"sneak", "sprint"}) {
                String key = "messages.action-names." + action;
                String previousDefault = action.equals("sneak") ? "&f&l웅크리기" : "&f&l스프린트";
                if (previousDefault.equals(next.getString(key))) {
                    next.set(key, next.getDefaultSection().getString(key));
                }
            }
            next.set("config-version", 7);
            File backup = new File(plugin.getDataFolder(), "config-v" + oldVersion + "-backup.yml");
            if (!backup.exists()) {
                Files.copy(configFile.toPath(), backup.toPath());
            }
            next.options().copyDefaults(true);
            next.save(configFile);
        }
        // Commit only after parsing and validation; failed reload retains the previous object.
        config = next;
        settings = nextSettings;
    }

    public GameSettings settings() { return settings; }
    public boolean flag(String key) { return config.getBoolean(key); }
    public Component text(String value) { return colors.deserialize(value); }
    public Component message(String key) {
        // The overload with an explicit fallback bypasses YAML defaults on an older/partial config.
        return text(Objects.requireNonNullElse(config.getString("messages." + key), ""));
    }
    public Component actionName(SyncAction action) { return message("action-names." + action.configKey()); }
    public Component prefixed(String key) {
        return message("prefix").append(message(key));
    }

    public Title startupTitle() {
        return Title.title(text(config.getString("startup.title", "일심동체")),
                text(config.getString("startup.subtitle", "mod by beddeak(침댁)")),
                Title.Times.times(ticks("startup.fade-in-ticks"), ticks("startup.stay-ticks"), ticks("startup.fade-out-ticks")));
    }

    private Duration ticks(String key) {
        return Duration.ofMillis(config.getLong(key) * 50L);
    }
}
