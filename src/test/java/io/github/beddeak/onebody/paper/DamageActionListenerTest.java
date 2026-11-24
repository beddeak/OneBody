package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.config.GameSettings;
import io.github.beddeak.onebody.game.ActiveSyncChallenge;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.GameRuntime;
import io.github.beddeak.onebody.game.MovementInput;
import io.github.beddeak.onebody.game.SyncAction;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

class DamageActionListenerTest {
    private final GameManager game = mock(GameManager.class);
    private final DamageEventGuard guard = new DamageEventGuard();
    private final DamageActionListener listener = new DamageActionListener(game, guard);
    private final UUID attackerId = UUID.randomUUID();
    private final UUID victimId = UUID.randomUUID();
    private final Player attacker = player(attackerId);
    private final Player victim = player(victimId);

    @Test void oneDamageHandlerObservesUncancelledEventsAtMonitor() throws Exception {
        EventHandler handler = DamageActionListener.class.getDeclaredMethod("damage", EntityDamageEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
        assertTrue(handler.ignoreCancelled());
        assertEquals(1, java.util.Arrays.stream(DamageActionListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class)).count());
    }

    @Test void ordinaryCustomDamageCountsWhenNotOurPunishment() {
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(damage(victim, DamageCause.CUSTOM));
        verify(game).action(victimId, SyncAction.TAKE_DAMAGE);
    }

    @Test void fallDamageIsSeparateFromGeneralDamage() {
        allow(victimId, SyncAction.FALL_DAMAGE);
        listener.damage(damage(victim, DamageCause.FALL));
        verify(game).action(victimId, SyncAction.FALL_DAMAGE);
        verify(game, never()).action(any(), eq(SyncAction.TAKE_DAMAGE));
    }

    @Test void cancellationAndNonPositiveFinalDamageCannotCount() {
        EntityDamageEvent cancelled = damage(victim, DamageCause.FALL);
        when(cancelled.isCancelled()).thenReturn(true);
        listener.damage(cancelled);
        for (double amount : new double[] {0, -1, Double.NaN}) {
            EntityDamageEvent event = damage(victim, DamageCause.FIRE);
            when(event.getDamage()).thenReturn(4.0);
            when(event.getFinalDamage()).thenReturn(amount);
            listener.damage(event);
        }
        verifyNoInteractions(game);
    }

    @Test void penaltyDamageCannotCountAsReceivedDamageOrPlayerAttack() {
        allow(victimId, SyncAction.TAKE_DAMAGE);
        allow(victimId, SyncAction.FALL_DAMAGE);
        allow(attackerId, SyncAction.ATTACK_PLAYER);
        clearInvocations(game);
        guard.runPenalty(() -> {
            listener.damage(damage(victim, DamageCause.CUSTOM));
            listener.damage(damage(victim, DamageCause.FALL));
            listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        });
        verifyNoInteractions(game);
    }

    @Test void directPlayerHitPrefersAttackerAndCallsActionOnce() {
        allow(attackerId, SyncAction.ATTACK_PLAYER);
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        verify(game).action(attackerId, SyncAction.ATTACK_PLAYER);
        verify(game, times(1)).action(any(), any());
    }

    @Test void directHitOnOtherEntityRemainsOrdinaryAttack() {
        allow(attackerId, SyncAction.ATTACK);
        listener.damage(hit(attacker, mock(Entity.class), DamageCause.ENTITY_ATTACK));
        verify(game).action(attackerId, SyncAction.ATTACK);
        verify(game, times(1)).action(any(), any());
    }

    @Test void ineligibleOrOutsideAttackerAllowsVictimsReceivedDamage() {
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        verify(game).action(victimId, SyncAction.TAKE_DAMAGE);
        verify(game, never()).action(attackerId, SyncAction.ATTACK_PLAYER);
    }

    @Test void disabledPlayerAttackFallsBackToEnabledReceivedDamage() {
        GameManager real = realGame(Set.of(SyncAction.TAKE_DAMAGE));
        new DamageActionListener(real, guard).damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        assertEquals(SyncAction.TAKE_DAMAGE, real.activeChallenge().action());
        assertEquals(victimId, real.activeChallenge().initiator());
    }

    @Test void unregisteredPlayerAttackerCannotHideRegisteredVictimsDamage() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        Player outsider = player(UUID.randomUUID());
        new DamageActionListener(real, guard).damage(hit(outsider, victim, DamageCause.ENTITY_ATTACK));
        assertEquals(SyncAction.TAKE_DAMAGE, real.activeChallenge().action());
        assertEquals(victimId, real.activeChallenge().initiator());
    }

    @Test void incomingMobProjectileAndSweepAreGeneralReceivedDamage() {
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(mock(Entity.class), victim, DamageCause.ENTITY_ATTACK));
        listener.damage(hit(mock(Projectile.class), victim, DamageCause.PROJECTILE));
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_SWEEP_ATTACK));
        verify(game, times(3)).action(victimId, SyncAction.TAKE_DAMAGE);
        verify(game, never()).action(attackerId, SyncAction.ATTACK_PLAYER);
        verify(game, never()).action(attackerId, SyncAction.ATTACK);
    }

    @Test void playerAttributedRangedDamageDoesNotCountAsMeleeAttack() {
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.PROJECTILE));
        verify(game).action(victimId, SyncAction.TAKE_DAMAGE);
        verify(game, never()).action(attackerId, SyncAction.ATTACK_PLAYER);
    }

    @Test void damageToNonPlayerWithoutPlayerMeleeAttackerCannotCount() {
        listener.damage(damage(mock(Entity.class), DamageCause.FALL));
        listener.damage(hit(mock(Entity.class), mock(Entity.class), DamageCause.ENTITY_ATTACK));
        verify(game, never()).action(any(), any());
    }

    @Test void activeGeneralDamageChallengeAcceptsVictimInsteadOfPlayerAttacker() {
        current(SyncAction.TAKE_DAMAGE);
        allow(attackerId, SyncAction.ATTACK_PLAYER);
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        verify(game).action(victimId, SyncAction.TAKE_DAMAGE);
        verify(game, times(1)).action(any(), any());
    }

    @Test void activePlayerAttackChallengeAcceptsAttackerInsteadOfVictim() {
        current(SyncAction.ATTACK_PLAYER);
        allow(attackerId, SyncAction.ATTACK_PLAYER);
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        verify(game).action(attackerId, SyncAction.ATTACK_PLAYER);
        verify(game, times(1)).action(any(), any());
    }

    @Test void fallDoesNotCompleteGeneralDamageAndOtherDamageDoesNotCompleteFall() {
        current(SyncAction.TAKE_DAMAGE);
        allow(victimId, SyncAction.FALL_DAMAGE);
        listener.damage(damage(victim, DamageCause.FALL));
        current(SyncAction.FALL_DAMAGE);
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(damage(victim, DamageCause.FIRE));
        verify(game, never()).action(any(), any());
    }

    @Test void unrelatedActiveChallengeIgnoresBothCandidates() {
        current(SyncAction.JUMP);
        allow(attackerId, SyncAction.ATTACK_PLAYER);
        allow(victimId, SyncAction.TAKE_DAMAGE);
        listener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        verify(game, never()).action(any(), any());
    }

    @Test void differingCausesAndAmountsStillCompleteTheSameGeneralDamageChallenge() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        DamageActionListener realListener = new DamageActionListener(real, guard);
        EntityDamageEvent first = damage(attacker, DamageCause.FIRE);
        when(first.getFinalDamage()).thenReturn(1.0);
        realListener.damage(first);
        assertEquals(SyncAction.TAKE_DAMAGE, real.activeChallenge().action());
        EntityDamageEvent second = damage(victim, DamageCause.PROJECTILE);
        when(second.getFinalDamage()).thenReturn(7.0);
        realListener.damage(second);
        assertNull(real.activeChallenge());
    }

    @Test void lastReceivedDamageResponseFromPvpCannotOpenPlayerAttackChallenge() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        real.action(attackerId, SyncAction.TAKE_DAMAGE);
        new DamageActionListener(real, guard).damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        assertNull(real.activeChallenge());
    }

    @Test void lastPlayerAttackResponseFromPvpCannotOpenReceivedDamageChallenge() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        real.action(victimId, SyncAction.ATTACK_PLAYER);
        new DamageActionListener(real, guard).damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        assertNull(real.activeChallenge());
    }

    @Test void playerHitCannotCompleteOrdinaryAttackChallengeButOtherEntityHitCan() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        real.action(victimId, SyncAction.ATTACK);
        ActiveSyncChallenge challenge = real.activeChallenge();
        DamageActionListener realListener = new DamageActionListener(real, guard);
        realListener.damage(hit(attacker, victim, DamageCause.ENTITY_ATTACK));
        assertSame(challenge, real.activeChallenge());
        assertEquals(Set.of(attackerId), challenge.pendingPlayers());
        realListener.damage(hit(attacker, mock(Entity.class), DamageCause.ENTITY_ATTACK));
        assertNull(real.activeChallenge());
    }

    @Test void ownPenaltyCannotCompleteAnActiveReceivedDamageChallenge() {
        GameManager real = realGame(EnumSet.allOf(SyncAction.class));
        real.action(attackerId, SyncAction.TAKE_DAMAGE);
        ActiveSyncChallenge challenge = real.activeChallenge();
        DamageActionListener realListener = new DamageActionListener(real, guard);
        guard.runPenalty(() -> realListener.damage(damage(victim, DamageCause.CUSTOM)));
        assertSame(challenge, real.activeChallenge());
        assertEquals(Set.of(victimId), challenge.pendingPlayers());
        realListener.damage(damage(victim, DamageCause.CUSTOM));
        assertNull(real.activeChallenge());
    }

    private void allow(UUID id, SyncAction action) {
        when(game.canAcceptAction(id, action)).thenReturn(true);
    }

    private void current(SyncAction action) {
        ActiveSyncChallenge active = mock(ActiveSyncChallenge.class);
        when(active.action()).thenReturn(action);
        when(game.activeChallenge()).thenReturn(active);
    }

    private GameManager realGame(Set<SyncAction> enabled) {
        GameRuntime runtime = mock(GameRuntime.class);
        when(runtime.onlinePlayers()).thenReturn(Set.of(attackerId, victimId));
        when(runtime.eligible(any())).thenReturn(true);
        when(runtime.movementInput(any())).thenReturn(MovementInput.NONE);
        when(runtime.currentTick()).thenReturn(100L);
        when(runtime.later(any(), anyLong())).thenReturn(mock(GameRuntime.Task.class));
        when(runtime.repeat(any(), anyLong(), anyLong())).thenReturn(mock(GameRuntime.Task.class));
        GameManager real = new GameManager(runtime, new GameSettings(2, 1.5, 20, enabled));
        assertTrue(real.start());
        return real;
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static EntityDamageEvent damage(Entity victim, DamageCause cause) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(cause);
        when(event.getFinalDamage()).thenReturn(1.0);
        return event;
    }

    private static EntityDamageByEntityEvent hit(Entity attacker, Entity victim, DamageCause cause) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(cause);
        when(event.getFinalDamage()).thenReturn(1.0);
        return event;
    }
}
