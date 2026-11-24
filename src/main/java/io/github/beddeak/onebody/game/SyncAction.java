package io.github.beddeak.onebody.game;

import java.util.Locale;

public enum SyncAction {
    JUMP, SNEAK, SPRINT, ATTACK, ATTACK_PLAYER, BLOCK_BREAK, BLOCK_PLACE, ITEM_USE,
    MOVE, TAKE_DAMAGE, FALL_DAMAGE, DOOR_USE, SWITCH_USE, CONTAINER_OPEN;

    public boolean stateful() { return this == MOVE || this == SNEAK || this == SPRINT; }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
