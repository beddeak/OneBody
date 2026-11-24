package io.github.beddeak.onebody.paper;

import static io.github.beddeak.onebody.paper.SharedInventoryManagerTest.stack;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SharedItemUseTest {
    @TempDir Path directory;
    final GameManager game = mock(GameManager.class);
    final SharedInventoryManagerTest.FakeHost host = new SharedInventoryManagerTest.FakeHost();
    final SharedInventoryManagerTest.Person first = new SharedInventoryManagerTest.Person();
    final SharedInventoryManagerTest.Person second = new SharedInventoryManagerTest.Person();
    SharedInventoryManager sharing;
    SharedItemUseListener listener;

    @BeforeEach void prepare() {
        host.directory = directory;
        host.people.put(first.id, first);
        host.people.put(second.id, second);
        var participants = new LinkedHashSet<>(List.of(first.id, second.id));
        when(game.running()).thenReturn(true);
        when(game.participants()).thenReturn(participants);
        when(game.eligibleParticipants()).thenReturn(participants);
        first.slots[0] = stack(Material.STONE, 2);
        first.slots[1] = stack(Material.STONE, 2);
        first.slots[40] = stack(Material.STONE, 2);
        sharing = new SharedInventoryManager(game, host);
        assertTrue(sharing.start(first.id));
        listener = new SharedItemUseListener(sharing);
    }

    @ParameterizedTest @EnumSource(Use.class)
    void conflictingUseIsCancelledUntilTheOwnersChangesHaveBeenPublished(Use kind) throws Exception {
        Cancellable accepted = event(kind, first);
        dispatch(kind, accepted);
        assertFalse(accepted.isCancelled());
        first.slots[0] = stack(Material.STONE, 1); // Model the accepted server-side consumption.
        Cancellable blocked = event(kind, second);
        dispatch(kind, blocked);
        assertTrue(blocked.isCancelled(), kind.name());
        sharing.flush(); // Incidental join/quit flush must not release in-flight reservations.
        assertEquals(1, second.slots[0].getAmount());
        Cancellable stillBlocked = event(kind, second);
        dispatch(kind, stillBlocked);
        assertTrue(stillBlocked.isCancelled());
        host.tick.run();
        Cancellable next = event(kind, second);
        dispatch(kind, next);
        assertFalse(next.isCancelled(), "reservation must not survive the next heartbeat");
    }

    @ParameterizedTest @EnumSource(Use.class)
    void alreadyCancelledEventsDoNotReserveItems(Use kind) throws Exception {
        Cancellable cancelled = event(kind, first);
        cancelled.setCancelled(true);
        dispatch(kind, cancelled);
        Cancellable next = event(kind, second);
        dispatch(kind, next);
        assertFalse(next.isCancelled());
    }

    @Test void lastBlockCanCreateOnlyOneWorldPlacementAndOneMissionAction() throws Exception {
        first.slots[0] = stack(Material.STONE, 1);
        sharing.flush();
        ActionListener actions = new ActionListener(game);
        int placed = 0;
        for (var person : List.of(first, second)) {
            var event = (BlockPlaceEvent) event(Use.PLACE, person);
            listener.placeBlock(event);
            // Bukkit skips ignoreCancelled MONITOR handlers and the world effect when cancelled.
            if (!event.isCancelled()) {
                actions.placeBlock(event);
                person.slots[0] = null;
                placed++;
            }
        }
        host.tick.run();
        assertEquals(1, placed);
        assertNull(first.slots[0]);
        assertNull(second.slots[0]);
        verify(game).action(first.id, SyncAction.BLOCK_PLACE);
        verify(game, never()).action(second.id, SyncAction.BLOCK_PLACE);
        assertTrue(ActionListener.class.getMethod("placeBlock", BlockPlaceEvent.class)
                .getAnnotation(EventHandler.class).ignoreCancelled());
    }

    @Test void twoMiningActionsCannotSpendOnlyOneDurabilityPoint() throws Exception {
        first.slots[0] = SharedDurabilityTest.item(10);
        sharing.flush();
        int broken = 0;
        for (var person : List.of(first, second)) {
            var event = event(Use.BREAK, person);
            dispatch(Use.BREAK, event);
            if (!event.isCancelled()) { broken++; person.slots[0] = SharedDurabilityTest.item(11); }
        }
        host.tick.run();
        assertEquals(1, broken);
        assertEquals(11, SharedDurabilityTest.damage(first.slots[0]));
        assertEquals(11, SharedDurabilityTest.damage(second.slots[0]));
    }

    @Test void passiveArmorWearFromBothPlayersIsAddedWithoutBlockingDamage() {
        first.slots[36] = SharedDurabilityTest.item(10);
        sharing.flush();
        first.slots[36] = SharedDurabilityTest.item(12);
        second.slots[36] = SharedDurabilityTest.item(13);
        host.tick.run();
        assertEquals(15, SharedDurabilityTest.damage(first.slots[36]));
        assertEquals(15, SharedDurabilityTest.damage(second.slots[36]));
    }

    @Test void differentHotbarSlotsCanBeUsedByDifferentPlayers() throws Exception {
        when(second.inventory.getHeldItemSlot()).thenReturn(1);
        Cancellable a = event(Use.BREAK, first), b = event(Use.BREAK, second);
        dispatch(Use.BREAK, a); dispatch(Use.BREAK, b);
        assertFalse(a.isCancelled()); assertFalse(b.isCancelled());
    }

    @Test void offhandConflictsWithOffhandButNotAnUnrelatedMainHand() throws Exception {
        var offhand = (BlockPlaceEvent) event(Use.PLACE, first);
        when(offhand.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.placeBlock(offhand);
        var main = (BlockPlaceEvent) event(Use.PLACE, second);
        listener.placeBlock(main);
        assertFalse(main.isCancelled());
        var otherOffhand = (BlockPlaceEvent) event(Use.PLACE, second);
        when(otherOffhand.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.placeBlock(otherOffhand);
        assertTrue(otherOffhand.isCancelled());
    }

    @Test void samePlayerNestedBowAndProjectileEventsRemainAllowed() throws Exception {
        for (Use kind : List.of(Use.LOAD, Use.SHOOT, Use.THROW, Use.LAUNCH, Use.ATTACK)) {
            var event = event(kind, first);
            dispatch(kind, event);
            assertFalse(event.isCancelled());
        }
    }

    @Test void wholeInventoryReservationIsAtomicWhenAnAmmoSlotIsAlreadyOwned() {
        assertTrue(sharing.claimUse(first.player, 40));
        assertFalse(sharing.claimInventoryUse(second.player));
        assertTrue(sharing.claimUse(first.player, 0), "failed claim must not keep its earlier slots");
    }

    @Test void guiCursorOwnerBlocksItemUseUntilCursorIsReturned() throws Exception {
        var click = cancellable(mock(InventoryClickEvent.class));
        when(click.getWhoClicked()).thenReturn(first.player);
        sharing.click(click);
        first.cursor = stack(Material.DIAMOND, 1);
        host.tick.run();
        var blocked = event(Use.CONSUME, second);
        dispatch(Use.CONSUME, blocked);
        assertTrue(blocked.isCancelled());
        first.cursor = null;
        host.tick.run();
        var allowed = event(Use.CONSUME, second);
        dispatch(Use.CONSUME, allowed);
        assertFalse(allowed.isCancelled());
    }

    @Test void itemUsePreventsOtherPlayersFromMovingTheSameInventory() {
        assertTrue(sharing.claimUse(first.player, 0));
        var click = cancellable(mock(InventoryClickEvent.class));
        when(click.getWhoClicked()).thenReturn(second.player);
        sharing.click(click);
        assertTrue(click.isCancelled());
        var swap = cancellable(mock(PlayerSwapHandItemsEvent.class));
        when(swap.getPlayer()).thenReturn(second.player);
        sharing.swapHands(swap);
        assertTrue(swap.isCancelled());
    }

    @Test void stopAndRestartClearReservationsAndDisabledSharingAllowsUse() {
        assertTrue(sharing.claimUse(first.player, 0));
        sharing.stop();
        assertTrue(sharing.claimUse(second.player, 0));
        assertTrue(sharing.start(first.id));
        assertTrue(sharing.claimUse(second.player, 0));
        var outsider = new SharedInventoryManagerTest.Person();
        assertTrue(sharing.claimUse(outsider.player, 0));
    }

    @Test void quitPublishesChangesAndReleasesTheQuittersReservations() {
        assertTrue(sharing.claimUse(first.player, 0));
        first.slots[0] = stack(Material.STONE, 1);
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(first.player);
        sharing.quit(quit);
        assertEquals(1, second.slots[0].getAmount());
        assertTrue(sharing.claimUse(second.player, 0));
    }

    @Test void noReservationsSurviveGroupDeath() {
        assertTrue(sharing.claimInventoryUse(first.player));
        var death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(first.player);
        when(death.getDrops()).thenReturn(new java.util.ArrayList<>());
        when(death.getItemsToKeep()).thenReturn(new java.util.ArrayList<>());
        sharing.died(death);
        assertTrue(sharing.claimInventoryUse(second.player));
    }

    @Test void emptyHandActionsAndEnvironmentalDamageDoNotClaimSharedTools() throws Exception {
        first.slots[0] = null;
        sharing.flush();
        assertTrue(sharing.claimInventoryUse(second.player));
        var event = event(Use.ATTACK, first);
        dispatch(Use.ATTACK, event);
        assertFalse(event.isCancelled());
        var indirect = (EntityDamageByEntityEvent) event(Use.ATTACK, second);
        when(indirect.getCause()).thenReturn(EntityDamageEvent.DamageCause.PROJECTILE);
        listener.attack(indirect);
        assertFalse(indirect.isCancelled());
    }

    @Test void guardsRunBeforeMissionObservers() throws Exception {
        for (Use kind : Use.values()) {
            var handler = SharedItemUseListener.class.getMethod(kind.method, kind.type).getAnnotation(EventHandler.class);
            assertEquals(EventPriority.HIGHEST, handler.priority());
            assertTrue(handler.ignoreCancelled());
        }
    }

    @Test void oneRemainingFoodHasOnlyOneConsumerAndLeavesNoPhantomStack() throws Exception {
        first.slots[0] = stack(Material.APPLE, 1);
        sharing.flush();
        int consumers = 0;
        for (var person : List.of(first, second)) {
            var event = event(Use.CONSUME, person);
            dispatch(Use.CONSUME, event);
            if (!event.isCancelled()) { consumers++; person.slots[0] = null; }
        }
        host.tick.run();
        assertEquals(1, consumers);
        assertNull(first.slots[0]); assertNull(second.slots[0]);
    }

    @Test void zeroDamageAttacksAndImpossiblePlacementsDoNotReserveAResource() throws Exception {
        var noDamage = (EntityDamageByEntityEvent) event(Use.ATTACK, first);
        when(noDamage.getFinalDamage()).thenReturn(0.0);
        listener.attack(noDamage);
        var impossible = (BlockPlaceEvent) event(Use.PLACE, first);
        when(impossible.canBuild()).thenReturn(false);
        listener.placeBlock(impossible);
        assertTrue(sharing.claimInventoryUse(second.player));
    }

    @Test void oneArrowCannotFeedDifferentBowsInTheSameTick() throws Exception {
        first.slots[0] = stack(Material.BOW, 1);
        first.slots[1] = stack(Material.BOW, 1);
        first.slots[9] = stack(Material.ARROW, 1);
        sharing.flush();
        when(second.inventory.getHeldItemSlot()).thenReturn(1);
        var firstShot = event(Use.SHOOT, first);
        dispatch(Use.SHOOT, firstShot);
        assertFalse(firstShot.isCancelled());
        first.slots[9] = null;
        var otherShot = event(Use.SHOOT, second);
        dispatch(Use.SHOOT, otherShot);
        assertTrue(otherShot.isCancelled());
        host.tick.run();
        assertNull(first.slots[9]); assertNull(second.slots[9]);
    }

    private void dispatch(Use kind, Cancellable event) throws Exception {
        SharedItemUseListener.class.getMethod(kind.method, kind.type).invoke(listener, event);
    }

    private Cancellable event(Use kind, SharedInventoryManagerTest.Person person) {
        Cancellable event = switch (kind) {
            case BREAK -> { var e = mock(BlockBreakEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
            case PLACE -> { var e = mock(BlockPlaceEvent.class); when(e.getPlayer()).thenReturn(person.player);
                when(e.getHand()).thenReturn(EquipmentSlot.HAND); when(e.canBuild()).thenReturn(true); yield e; }
            case ATTACK -> { var e = mock(EntityDamageByEntityEvent.class); when(e.getDamager()).thenReturn(person.player);
                when(e.getFinalDamage()).thenReturn(2.0); when(e.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK); yield e; }
            case CONSUME -> { var e = mock(PlayerItemConsumeEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
            case EMPTY -> { var e = mock(PlayerBucketEmptyEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
            case FILL -> { var e = mock(PlayerBucketFillEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
            case SHOOT -> { var e = mock(EntityShootBowEvent.class); when(e.getEntity()).thenReturn(person.player); yield e; }
            case LOAD -> { var e = mock(EntityLoadCrossbowEvent.class); when(e.getEntity()).thenReturn(person.player); yield e; }
            case THROW -> { var e = mock(PlayerLaunchProjectileEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
            case LAUNCH -> { var e = mock(ProjectileLaunchEvent.class); var projectile = mock(Projectile.class);
                when(e.getEntity()).thenReturn(projectile); when(projectile.getShooter()).thenReturn(person.player); yield e; }
            case ENTITY_PLACE -> { var e = mock(EntityPlaceEvent.class); when(e.getPlayer()).thenReturn(person.player);
                when(e.getHand()).thenReturn(EquipmentSlot.HAND); yield e; }
            case ENTITY_INTERACT -> { var e = mock(PlayerInteractEntityEvent.class); when(e.getPlayer()).thenReturn(person.player);
                when(e.getHand()).thenReturn(EquipmentSlot.HAND); yield e; }
            case SHEAR -> { var e = mock(PlayerShearEntityEvent.class); when(e.getPlayer()).thenReturn(person.player);
                when(e.getHand()).thenReturn(EquipmentSlot.HAND); yield e; }
            case FERTILIZE -> { var e = mock(BlockFertilizeEvent.class); when(e.getPlayer()).thenReturn(person.player); yield e; }
        };
        return cancellable(event);
    }

    private static <T extends Cancellable> T cancellable(T event) {
        var cancelled = new AtomicBoolean();
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        return event;
    }

    enum Use {
        BREAK("breakBlock", BlockBreakEvent.class), PLACE("placeBlock", BlockPlaceEvent.class),
        ATTACK("attack", EntityDamageByEntityEvent.class), CONSUME("consume", PlayerItemConsumeEvent.class),
        EMPTY("emptyBucket", PlayerBucketEmptyEvent.class), FILL("fillBucket", PlayerBucketFillEvent.class),
        SHOOT("shoot", EntityShootBowEvent.class), LOAD("load", EntityLoadCrossbowEvent.class),
        THROW("throwItem", PlayerLaunchProjectileEvent.class), LAUNCH("launch", ProjectileLaunchEvent.class),
        ENTITY_PLACE("placeEntity", EntityPlaceEvent.class), ENTITY_INTERACT("interactEntity", PlayerInteractEntityEvent.class),
        SHEAR("shear", PlayerShearEntityEvent.class), FERTILIZE("fertilize", BlockFertilizeEvent.class);
        final String method;
        final Class<?> type;
        Use(String method, Class<?> type) { this.method = method; this.type = type; }
    }
}
