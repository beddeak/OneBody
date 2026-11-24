package io.github.beddeak.onebody.paper;

import org.bukkit.entity.Player;

final class PenaltyDamage {
    private PenaltyDamage() { }

    static void apply(Player player, double amount) {
        // Vanilla hurt immunity otherwise suppresses equal damage on consecutive ticks.
        // Temporarily bypass only this call, without changing maximum immunity or leaving it disabled.
        int previousTicks = player.getNoDamageTicks();
        double previousDamage = player.getLastDamage();
        try {
            player.setNoDamageTicks(0);
            player.damage(amount);
        } finally {
            if (!player.isDead()) {
                player.setNoDamageTicks(previousTicks);
                player.setLastDamage(previousDamage);
            }
        }
    }
}
