package io.github.beddeak.onebody.config;

import io.github.beddeak.onebody.game.SyncAction;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable settings captured by each challenge, so reload cannot change its rules halfway. */
public record GameSettings(int toleranceTicks, double damageAmount, int damageIntervalTicks,
                           Set<SyncAction> enabledActions) {
    public GameSettings {
        if (toleranceTicks < 1 || damageIntervalTicks < 1
                || !Double.isFinite(damageAmount) || damageAmount <= 0) {
            throw new IllegalArgumentException("판정/피해 주기는 양의 정수, 피해량은 유한한 양수여야 합니다.");
        }
        enabledActions = Set.copyOf(enabledActions);
    }

    public static GameSettings read(ConfigurationSection config) {
        Object amount = config.get("damage.amount-per-tick");
        if (!(amount instanceof Number number)) {
            throw new IllegalArgumentException("damage.amount-per-tick는 숫자여야 합니다.");
        }
        Set<SyncAction> actions = EnumSet.noneOf(SyncAction.class);
        for (SyncAction action : SyncAction.values()) {
            String key = "actions." + action.configKey();
            requireBoolean(config, key);
            if (config.getBoolean(key)) {
                actions.add(action);
            }
        }
        return new GameSettings(positiveInt(config, "sync-tolerance-ticks"), number.doubleValue(),
                positiveInt(config, "damage.interval-ticks"), actions);
    }

    public static int positiveInt(ConfigurationSection config, String key) {
        return integer(config, key, 1);
    }

    public static int nonNegativeInt(ConfigurationSection config, String key) {
        return integer(config, key, 0);
    }

    private static int integer(ConfigurationSection config, String key, int minimum) {
        Object raw = config.get(key);
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue() || number.intValue() < minimum) {
            throw new IllegalArgumentException(key + "는 " + minimum + " 이상의 정수여야 합니다.");
        }
        return number.intValue();
    }

    public static void requireBoolean(ConfigurationSection config, String key) {
        if (!(config.get(key) instanceof Boolean)) {
            throw new IllegalArgumentException(key + "는 true/false여야 합니다.");
        }
    }
}
