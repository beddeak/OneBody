package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PenaltyDamageTest {
    @Test void eachPenaltyBypassesHurtImmunityWithoutLeavingPlayerStateChanged() {
        Player player = mock(Player.class);
        when(player.getNoDamageTicks()).thenReturn(12);
        when(player.getLastDamage()).thenReturn(4.0);
        PenaltyDamage.apply(player, 1.0);
        PenaltyDamage.apply(player, 1.0);
        InOrder order = inOrder(player);
        for (int pulse = 0; pulse < 2; pulse++) {
            order.verify(player).getNoDamageTicks();
            order.verify(player).getLastDamage();
            order.verify(player).setNoDamageTicks(0);
            order.verify(player).damage(1.0);
            order.verify(player).isDead();
            order.verify(player).setNoDamageTicks(12);
            order.verify(player).setLastDamage(4.0);
        }
        verify(player, never()).setMaximumNoDamageTicks(anyInt());
    }

    @Test void exceptionStillRestoresTemporaryImmunityState() {
        Player player = mock(Player.class);
        when(player.getNoDamageTicks()).thenReturn(9);
        when(player.getLastDamage()).thenReturn(3.0);
        doThrow(new IllegalStateException("other listener failed")).when(player).damage(1.0);
        assertThrows(IllegalStateException.class, () -> PenaltyDamage.apply(player, 1));
        verify(player).setNoDamageTicks(9);
        verify(player).setLastDamage(3.0);
    }

    @Test void deathDoesNotRestoreStaleCombatStateOntoDeadPlayer() {
        Player player = mock(Player.class);
        when(player.isDead()).thenReturn(true);
        PenaltyDamage.apply(player, 1);
        verify(player, times(1)).setNoDamageTicks(0);
        verify(player, never()).setLastDamage(anyDouble());
    }
}
