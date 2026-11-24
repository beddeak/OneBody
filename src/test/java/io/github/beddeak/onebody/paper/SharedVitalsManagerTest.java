package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.github.beddeak.onebody.game.GameManager;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class SharedVitalsManagerTest {
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final GameManager game = mock(GameManager.class);
    private final Server server = mock(Server.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final BukkitTask task = mock(BukkitTask.class);
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final AtomicBoolean penalty = new AtomicBoolean();
    private final SharedVitalsManager manager = new SharedVitalsManager(plugin, game, player -> penalty.get());
    private final Person first = person(20, 20);
    private final Person second = person(20, 20);

    SharedVitalsManagerTest() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
        when(game.running()).thenReturn(true);
        when(game.participants()).thenAnswer(invocation -> participants);
        install(first);
        install(second);
    }

    @Test void startCopiesChosenPlayersHealthAndAllHungerFields() {
        first.health = 12;
        first.food = 14;
        first.saturation = 3.5f;
        first.exhaustion = 2.5f;
        manager.start(first.id);
        assertVitals(second, 12, 14, 3.5f, 2.5f);
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L));
    }

    @Test void damageIsSharedAfterActualApplicationInsteadOfPredictingFromEventAmount() {
        manager.start(first.id);
        EntityDamageEvent event = damage(first, 9);
        manager.damage(event);
        assertEquals(20, second.health);
        first.health = 17; // Final health loss can differ from the event's reported amount.
        manager.synchronize();
        assertEquals(17, first.health);
        assertEquals(17, second.health);
        manager.synchronize();
        assertEquals(17, second.health, "setter replication must not be counted again");
        verify(first.player, never()).damage(anyDouble());
        verify(second.player, never()).damage(anyDouble());
    }

    @Test void simultaneousExternalHitsAreCombinedWithoutLastPlayerOverwritingTheOther() {
        manager.start(first.id);
        manager.damage(damage(first, 3));
        first.health = 17;
        manager.damage(damage(second, 4));
        second.health = 16;
        manager.synchronize();
        assertEquals(13, first.health);
        assertEquals(13, second.health);
    }

    @Test void allPlayerPenaltyIsCountedOnceDespiteDifferentDefenseResults() {
        manager.start(first.id);
        penalty.set(true);
        manager.damage(damage(first, 1.5));
        first.health = 18.5;
        manager.damage(damage(second, 0.75));
        second.health = 19.25;
        penalty.set(false);
        manager.synchronize();
        assertEquals(18.5, first.health);
        assertEquals(18.5, second.health);
        manager.synchronize();
        assertEquals(18.5, second.health);
    }

    @Test void penaltyAndExternalDamageOnSamePlayerInSameTickRemainDistinct() {
        manager.start(first.id);
        manager.damage(damage(first, 2));
        first.health = 18;
        penalty.set(true);
        manager.damage(damage(first, 1.5));
        first.health = 16.5;
        manager.damage(damage(second, 1.5));
        second.health = 18.5;
        penalty.set(false);
        manager.damage(damage(first, 1));
        first.health = 15.5;
        manager.synchronize();
        assertEquals(15.5, second.health, "3 external loss plus one 1.5 group penalty");
    }

    @Test void cancelledAndFullyAbsorbedDamageDoNotManufactureHealthLoss() {
        manager.start(first.id);
        EntityDamageEvent cancelled = damage(first, 10);
        when(cancelled.isCancelled()).thenReturn(true);
        penalty.set(true);
        manager.damage(cancelled);
        manager.damage(damage(second, 0));
        penalty.set(false);
        manager.synchronize();
        assertEquals(20, first.health);
        assertEquals(20, second.health);
    }

    @Test void actualHealingIsSharedAndCancelledHealingIsIgnored() {
        first.health = 10;
        manager.start(first.id);
        EntityRegainHealthEvent cancelled = regain(first, EntityRegainHealthEvent.RegainReason.MAGIC);
        when(cancelled.isCancelled()).thenReturn(true);
        manager.regain(cancelled);
        manager.synchronize();
        assertEquals(10, second.health);
        manager.regain(regain(first, EntityRegainHealthEvent.RegainReason.MAGIC));
        first.health = 14;
        manager.synchronize();
        assertEquals(14, second.health);
    }

    @Test void simultaneousPassiveRegenerationHealsOnlyOnePlayersAmount() {
        first.health = 10;
        manager.start(first.id);
        manager.regain(regain(first, EntityRegainHealthEvent.RegainReason.SATIATED));
        first.health = 11;
        manager.regain(regain(second, EntityRegainHealthEvent.RegainReason.SATIATED));
        second.health = 11;
        manager.synchronize();
        assertEquals(11, first.health);
        assertEquals(11, second.health);
    }

    @Test void eatingSharesFoodSaturationAndExhaustionAfterVanillaConsumption() {
        first.food = 10;
        first.saturation = 0;
        manager.start(first.id);
        PlayerItemConsumeEvent event = mock(PlayerItemConsumeEvent.class);
        when(event.getPlayer()).thenReturn(first.player);
        manager.consumed(event);
        first.food = 16;
        first.saturation = 7.2f;
        first.exhaustion = 0.4f;
        manager.synchronize();
        assertVitals(second, 20, 16, 7.2f, 0.4f);
        manager.synchronize();
        assertVitals(first, 20, 16, 7.2f, 0.4f);
        assertVitals(second, 20, 16, 7.2f, 0.4f);
    }

    @Test void identicalNaturalHungerTicksAreCopiedOnceWithoutParticipantMultiplier() {
        first.food = 18;
        first.saturation = 0;
        first.exhaustion = 3.9f;
        manager.start(first.id);
        first.food = second.food = 17;
        first.exhaustion = second.exhaustion = 0.1f;
        manager.synchronize();
        assertVitals(first, 20, 17, 0, 0.1f);
        assertVitals(second, 20, 17, 0, 0.1f);
        manager.synchronize();
        assertEquals(17, second.food);
    }

    @Test void eventSelectedFoodSourceWinsOverAnotherPlayersDifferentNaturalChange() {
        manager.start(first.id);
        FoodLevelChangeEvent event = mock(FoodLevelChangeEvent.class);
        when(event.getEntity()).thenReturn(first.player);
        manager.foodChanged(event);
        first.food = 19;
        second.food = 18;
        manager.synchronize();
        assertEquals(19, first.food);
        assertEquals(19, second.food);
    }

    @Test void newJoinerReceivesGroupValuesAndDoesNotReplaceThem() {
        first.health = 13;
        first.food = 11;
        manager.start(first.id);
        Person newcomer = person(20, 20);
        install(newcomer);
        manager.connected(newcomer.player);
        assertVitals(newcomer, 13, 11, 5, 0);
        manager.synchronize();
        assertEquals(13, first.health);
    }

    @Test void maxHealthClampDoesNotLookLikeRepeatedDamageInFollowingTicks() {
        second.maximum = 10;
        manager.start(first.id);
        assertEquals(10, first.health);
        assertEquals(10, second.health);
        manager.synchronize();
        manager.synchronize();
        assertEquals(10, first.health);
        assertEquals(10, second.health);
        manager.damage(damage(second, 2));
        second.health = 8;
        manager.synchronize();
        assertEquals(8, first.health);
        assertEquals(8, second.health, "shared current health respects the lowest effective maximum");
    }

    @Test void newLowerMaximumJoinerCapsCurrentHealthForEveryoneImmediately() {
        manager.start(first.id);
        manager.damage(damage(first, 2));
        first.health = 18;
        Person newcomer = person(10, 10);
        install(newcomer);
        manager.connected(newcomer.player);
        assertEquals(10, first.health);
        assertEquals(10, second.health);
        assertEquals(10, newcomer.health);
        manager.synchronize();
        assertEquals(10, first.health);
    }

    @Test void fatalDamageSharesZeroWithoutCallingSetHealthOnAlreadyDeadPlayer() {
        manager.start(first.id);
        manager.damage(damage(first, 30));
        first.health = 0;
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(first.player);
        manager.died(event);
        clearInvocations(first.player);
        manager.synchronize();
        assertEquals(0, second.health);
        verify(first.player, never()).setHealth(anyDouble());
        manager.stop();
        assertEquals(0, first.health);
        assertEquals(0, second.health);
    }

    @Test void firstRealRespawnAfterWipeStartsNewLifeAndLaterRespawnsJoinIt() {
        manager.start(first.id);
        manager.damage(damage(first, 30));
        first.health = 0;
        manager.synchronize();
        assertEquals(0, second.health);
        first.health = 20;
        PlayerPostRespawnEvent event = mock(PlayerPostRespawnEvent.class);
        when(event.getPlayer()).thenReturn(first.player);
        manager.respawned(event);
        assertEquals(0, second.health, "sharing must never resurrect the other dead player");
        manager.damage(damage(first, 2));
        first.health = 18;
        manager.synchronize();
        second.health = 20;
        manager.connected(second.player);
        assertEquals(18, second.health);
    }

    @Test void confirmedDeathOverridesSameTickHealingAndCannotBeErasedByImmediateRespawn() {
        first.health = 10;
        manager.start(first.id);
        manager.damage(damage(first, 30));
        first.health = 0;
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(first.player);
        manager.died(event);
        manager.regain(regain(second, EntityRegainHealthEvent.RegainReason.MAGIC));
        second.health = 14;
        first.health = 20; // Instant respawn occurs before the shared heartbeat.
        manager.connected(first.player);
        assertEquals(0, first.health);
        assertEquals(0, second.health);
    }

    @Test void stopCancelsTaskAndPreservesFinalHealthAndFoodWithoutRestoringOrReviving() {
        manager.start(first.id);
        manager.stop();
        verify(task).cancel();
        first.health = 9;
        first.food = 4;
        manager.synchronize();
        assertEquals(20, second.health);
        assertEquals(20, second.food);
    }

    @Test void removedPlayersAndSpectatorsCannotChangeTheGroup() {
        manager.start(first.id);
        participants.remove(first.id);
        first.health = 1;
        manager.synchronize();
        assertEquals(20, second.health);
        participants.add(first.id);
        first.mode = GameMode.SPECTATOR;
        manager.synchronize();
        assertEquals(20, second.health);
        assertEquals(1, first.health);
    }

    @Test void actualDamageMustBeSharedBeforeQuitRemovesItsObservation() throws Exception {
        manager.start(first.id);
        manager.damage(damage(first, 3));
        first.health = 17;
        var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(first.player);
        // The handler must run before ParticipantListener's MONITOR removal.
        var handler = SharedVitalsManager.class.getMethod("quit", org.bukkit.event.player.PlayerQuitEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertEquals(org.bukkit.event.EventPriority.HIGHEST, handler.priority());
        manager.quit(quit);
        participants.remove(first.id);
        manager.synchronize();
        assertEquals(17, second.health);
    }

    @Test void foodConsumedImmediatelyBeforeQuitIsStillSharedOnce() {
        first.food = 10;
        manager.start(first.id);
        var consume = mock(PlayerItemConsumeEvent.class);
        when(consume.getPlayer()).thenReturn(first.player);
        manager.consumed(consume);
        first.food = 16;
        var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(first.player);
        manager.quit(quit);
        participants.remove(first.id);
        manager.synchronize();
        assertEquals(16, second.food);
    }

    @Test void switchingToSpectatorCannotDiscardJustAppliedDamage() {
        manager.start(first.id);
        manager.damage(damage(first, 4));
        first.health = 16;
        var change = mock(org.bukkit.event.player.PlayerGameModeChangeEvent.class);
        when(change.getPlayer()).thenReturn(first.player);
        when(change.getNewGameMode()).thenReturn(GameMode.SPECTATOR);
        manager.spectator(change);
        first.mode = GameMode.SPECTATOR;
        manager.synchronize();
        assertEquals(16, second.health);
        first.health = 1;
        manager.synchronize();
        assertEquals(16, second.health, "later spectator changes are still excluded");
    }

    @Test void reentrantQuitDuringSharedHealthApplicationCannotApplyDeltaTwice() {
        manager.start(first.id);
        manager.damage(damage(first, 4));
        first.health = 16;
        var quit = mock(org.bukkit.event.player.PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(second.player);
        doAnswer(call -> {
            second.health = call.getArgument(0);
            manager.quit(quit);
            return null;
        }).when(second.player).setHealth(anyDouble());
        assertDoesNotThrow(manager::synchronize);
        assertEquals(16, first.health);
        assertEquals(16, second.health);
        manager.synchronize();
        assertEquals(16, second.health);
    }

    @Test void individualSharingFlagsAreRespected() {
        first.health = 12;
        first.food = 9;
        manager.start(first.id, false, true);
        assertEquals(20, second.health);
        assertEquals(9, second.food);
        manager.start(first.id, true, false);
        second.food = 3;
        first.food = 18;
        first.health = 11;
        manager.synchronize();
        assertEquals(11, second.health);
        assertEquals(3, second.food);
    }

    @Test void inactiveGameOrBothFlagsDisabledCannotStartARecurringTask() {
        when(game.running()).thenReturn(false);
        manager.start(first.id);
        verifyNoInteractions(scheduler);
        when(game.running()).thenReturn(true);
        manager.start(first.id, false, false);
        verifyNoInteractions(scheduler);
    }

    @Test void explicitStartupRollbackRestoresOriginalVitalsAndCancelsTheTask() {
        first.health = 12;
        first.food = 9;
        second.health = 18;
        second.food = 16;
        second.saturation = 2;
        second.exhaustion = 1;
        manager.start(first.id);
        assertEquals(12, second.health);
        manager.rollbackStart();
        assertVitals(first, 12, 9, 5, 0);
        assertVitals(second, 18, 16, 2, 1);
        verify(task).cancel();
        first.health = 4;
        manager.synchronize();
        assertEquals(18, second.health);
    }

    @Test void schedulerStartupFailureRestoresOriginalVitalsBeforeRethrowing() {
        first.health = 12;
        first.food = 9;
        second.health = 18;
        second.food = 16;
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));
        assertThrows(IllegalStateException.class, () -> manager.start(first.id));
        assertVitals(first, 12, 9, 5, 0);
        assertVitals(second, 18, 16, 5, 0);
    }

    @Test void committedStartupAndDeadPlayersAreNeverRestoredByRollback() {
        first.health = 12;
        manager.start(first.id);
        manager.commitStart();
        first.health = 0;
        second.health = 7;
        manager.rollbackStart();
        assertEquals(0, first.health);
        assertEquals(7, second.health);
    }

    private EntityDamageEvent damage(Person person, double finalDamage) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(person.player);
        when(event.getFinalDamage()).thenReturn(finalDamage);
        return event;
    }

    private EntityRegainHealthEvent regain(Person person, EntityRegainHealthEvent.RegainReason reason) {
        EntityRegainHealthEvent event = mock(EntityRegainHealthEvent.class);
        when(event.getEntity()).thenReturn(person.player);
        when(event.getRegainReason()).thenReturn(reason);
        when(event.getAmount()).thenReturn(4.0);
        return event;
    }

    private void install(Person person) {
        participants.add(person.id);
        when(server.getPlayer(person.id)).thenReturn(person.player);
    }

    @SuppressWarnings("deprecation")
    private static Person person(double health, double maximum) {
        Person person = new Person();
        person.health = health;
        person.maximum = maximum;
        when(person.player.getUniqueId()).thenReturn(person.id);
        when(person.player.isOnline()).thenReturn(true);
        when(person.player.isDead()).thenAnswer(invocation -> person.health <= 0);
        when(person.player.getGameMode()).thenAnswer(invocation -> person.mode);
        when(person.player.getHealth()).thenAnswer(invocation -> person.health);
        when(person.player.getMaxHealth()).thenAnswer(invocation -> person.maximum);
        when(person.player.getFoodLevel()).thenAnswer(invocation -> person.food);
        when(person.player.getSaturation()).thenAnswer(invocation -> person.saturation);
        when(person.player.getExhaustion()).thenAnswer(invocation -> person.exhaustion);
        doAnswer(invocation -> { person.health = invocation.getArgument(0); return null; }).when(person.player).setHealth(anyDouble());
        doAnswer(invocation -> { person.food = invocation.getArgument(0); return null; }).when(person.player).setFoodLevel(anyInt());
        doAnswer(invocation -> { person.saturation = invocation.getArgument(0); return null; }).when(person.player).setSaturation(anyFloat());
        doAnswer(invocation -> { person.exhaustion = invocation.getArgument(0); return null; }).when(person.player).setExhaustion(anyFloat());
        return person;
    }

    private static void assertVitals(Person person, double health, int food, float saturation, float exhaustion) {
        assertEquals(health, person.health);
        assertEquals(food, person.food);
        assertEquals(saturation, person.saturation);
        assertEquals(exhaustion, person.exhaustion);
    }

    private static final class Person {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        double health;
        double maximum;
        int food = 20;
        float saturation = 5;
        float exhaustion;
        GameMode mode = GameMode.SURVIVAL;
    }
}
