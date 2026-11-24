package io.github.beddeak.onebody.paper;

import io.github.beddeak.onebody.game.ActiveSyncChallenge;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/** Routes each damage event to at most one attack or received-damage action. */
public final class DamageActionListener implements Listener {
    private final GameManager game;
    private final DamageEventGuard penaltyGuard;

    public DamageActionListener(GameManager game, DamageEventGuard penaltyGuard) {
        this.game = game;
        this.penaltyGuard = penaltyGuard;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (penaltyGuard.active() || event.isCancelled() || !(event.getFinalDamage() > 0)) {
            return;
        }

        // A PvP hit is both an attack and received damage. Snapshot before responding so
        // a last response cannot finish one challenge and open another from the same event.
        ActiveSyncChallenge active = game.activeChallenge();
        if (event instanceof EntityDamageByEntityEvent byEntity
                && event.getCause() == DamageCause.ENTITY_ATTACK
                && byEntity.getDamager() instanceof Player attacker) {
            SyncAction attack = event.getEntity() instanceof Player
                    ? SyncAction.ATTACK_PLAYER : SyncAction.ATTACK;
            if ((active == null || active.action() == attack)
                    && game.canAcceptAction(attacker.getUniqueId(), attack)) {
                game.action(attacker.getUniqueId(), attack);
                return;
            }
        }

        if (event.getEntity() instanceof Player victim) {
            SyncAction received = event.getCause() == DamageCause.FALL
                    ? SyncAction.FALL_DAMAGE : SyncAction.TAKE_DAMAGE;
            if ((active == null || active.action() == received)
                    && game.canAcceptAction(victim.getUniqueId(), received)) {
                game.action(victim.getUniqueId(), received);
            }
        }
    }
}
