package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.game.GameManager;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedInventoryManagerTest {
    @TempDir Path directory;
    private final GameManager game = mock(GameManager.class);
    private final Set<UUID> participants = new LinkedHashSet<>();
    private final FakeHost host = new FakeHost();
    private SharedInventoryManager sharing;
    private Person leader;
    private Person other;

    @BeforeEach void prepare() {
        host.directory = directory;
        leader = addPerson();
        other = addPerson();
        when(game.running()).thenReturn(true);
        when(game.participants()).thenReturn(participants);
        when(game.eligibleParticipants()).thenReturn(participants);
        sharing = new SharedInventoryManager(game, host);
    }

    @Test void startsFromLeaderForAllFortyOneSlotsAndBacksUpEachOriginal() throws Exception {
        leader.slots[0] = stack(Material.STONE, 12);
        leader.slots[35] = stack(Material.DIAMOND, 2);
        leader.slots[36] = stack(Material.IRON_BOOTS, 1);
        leader.slots[39] = stack(Material.IRON_HELMET, 1);
        leader.slots[40] = stack(Material.SHIELD, 1);
        other.slots[0] = stack(Material.APPLE, 8);
        assertTrue(sharing.start(leader.id));
        assertTrue(sharing.active());
        for (int slot : new int[] {0, 35, 36, 39, 40}) {
            assertStack(leader.slots[slot].getType(), leader.slots[slot].getAmount(), other.slots[slot]);
            assertNotSame(leader.slots[slot], other.slots[slot]);
        }
        assertTrue(Files.exists(directory.resolve("pending").resolve(leader.id + ".inventory")));
        assertTrue(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
        assertNotNull(host.tick);
    }

    @Test void ordinaryChangesPropagateOnNextTickAndCanComeFromEitherParticipant() {
        leader.slots[0] = stack(Material.STONE, 10);
        sharing.start(leader.id);
        other.slots[0] = stack(Material.STONE, 7);
        assertStack(Material.STONE, 10, leader.slots[0]);
        host.tick.run();
        assertStack(Material.STONE, 7, leader.slots[0]);
        leader.slots[40] = stack(Material.SHIELD, 1);
        host.tick.run();
        assertStack(Material.SHIELD, 1, other.slots[40]);
        host.tick.run();
        assertStack(Material.STONE, 7, other.slots[0]);
    }

    @Test void simultaneousConsumptionSubtractsBothPlayersAmountsWithoutCancellingGameplay() {
        leader.slots[0] = stack(Material.APPLE, 10);
        sharing.start(leader.id);
        leader.slots[0] = stack(Material.APPLE, 9);
        other.slots[0] = stack(Material.APPLE, 8);
        host.tick.run();
        assertStack(Material.APPLE, 7, leader.slots[0]);
        assertStack(Material.APPLE, 7, other.slots[0]);
    }

    @Test void consumptionCannotCreateNegativeStacksWhenSeveralPlayersUseTheLastItem() {
        leader.slots[0] = stack(Material.APPLE, 1);
        sharing.start(leader.id);
        leader.slots[0] = null;
        other.slots[0] = null;
        host.tick.run();
        assertNull(leader.slots[0]);
        assertNull(other.slots[0]);
    }

    @Test void differentSlotChangesFromTwoPlayersAreBothRetained() {
        sharing.start(leader.id);
        leader.slots[3] = stack(Material.STONE, 3);
        other.slots[8] = stack(Material.DIAMOND, 1);
        host.tick.run();
        assertStack(Material.STONE, 3, other.slots[3]);
        assertStack(Material.DIAMOND, 1, leader.slots[8]);
    }

    @Test void sharedWritesDoNotBecomeNewInventoryChangesOnFollowingTicks() {
        leader.slots[0] = stack(Material.APPLE, 5);
        sharing.start(leader.id);
        other.slots[0] = stack(Material.APPLE, 4);
        host.tick.run();
        for (int tick = 0; tick < 5; tick++) { host.tick.run(); }
        assertStack(Material.APPLE, 4, leader.slots[0]);
        assertStack(Material.APPLE, 4, other.slots[0]);
    }

    @Test void secondGuiEditorWaitsUntilNextTickAndCursorKeepsOwnership() {
        sharing.start(leader.id);
        InventoryClickEvent first = mock(InventoryClickEvent.class);
        when(first.getWhoClicked()).thenReturn(leader.player);
        InventoryDragEvent second = mock(InventoryDragEvent.class);
        when(second.getWhoClicked()).thenReturn(other.player);
        sharing.click(first);
        sharing.drag(second);
        verify(first, never()).setCancelled(true);
        verify(second).setCancelled(true);
        leader.cursor = stack(Material.DIAMOND, 1);
        host.tick.run();
        clearInvocations(second);
        sharing.drag(second);
        verify(second).setCancelled(true);
        leader.cursor = null;
        host.tick.run();
        clearInvocations(second);
        sharing.drag(second);
        verify(second, never()).setCancelled(true);
    }

    @Test void cursorPickupThenOtherPlayersHandSwapCannotDuplicateTheStack() {
        leader.slots[0] = stack(Material.DIAMOND, 64);
        sharing.start(leader.id);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(leader.player);
        sharing.click(click);
        verify(click, never()).setCancelled(true);
        leader.cursor = leader.slots[0];
        leader.slots[0] = null;

        PlayerSwapHandItemsEvent swap = swapHands(other);
        assertTrue(swap.isCancelled());
        host.tick.run();
        assertStack(Material.DIAMOND, 64, leader.cursor);
        assertNull(leader.slots[0]);
        assertNull(other.slots[0]);
        assertNull(leader.slots[40]);
        assertNull(other.slots[40]);
    }

    @Test void handSwapThenOtherPlayersCursorPickupWaitsForPublishedSlots() {
        leader.slots[0] = stack(Material.DIAMOND, 64);
        sharing.start(leader.id);
        assertFalse(swapHands(other).isCancelled());
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(leader.player);
        sharing.click(click);
        verify(click).setCancelled(true);
        host.tick.run();
        assertNull(leader.slots[0]);
        assertStack(Material.DIAMOND, 64, leader.slots[40]);

        // The next tick accepts a fresh click against the published offhand contents.
        InventoryClickEvent nextClick = mock(InventoryClickEvent.class);
        when(nextClick.getWhoClicked()).thenReturn(leader.player);
        sharing.click(nextClick);
        verify(nextClick, never()).setCancelled(true);
        leader.cursor = leader.slots[40];
        leader.slots[40] = null;
        host.tick.run();
        assertStack(Material.DIAMOND, 64, leader.cursor);
        assertNull(other.slots[40]);
    }

    @Test void heldCursorBlocksHandSwapUntilReturnedAndPublished() {
        leader.slots[0] = stack(Material.DIAMOND, 64);
        sharing.start(leader.id);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(leader.player);
        sharing.click(click);
        leader.cursor = leader.slots[0];
        leader.slots[0] = null;
        host.tick.run();
        assertTrue(swapHands(other).isCancelled());
        leader.slots[0] = leader.cursor;
        leader.cursor = null;
        assertTrue(swapHands(other).isCancelled());
        host.tick.run();
        assertFalse(swapHands(other).isCancelled());
        host.tick.run();
        assertNull(leader.slots[0]);
        assertStack(Material.DIAMOND, 64, leader.slots[40]);
        assertStack(Material.DIAMOND, 64, other.slots[40]);
    }

    @Test void ownerCanSwapBothOccupiedHandsAndSwapBackInTheSameTick() {
        leader.slots[0] = stack(Material.STONE, 10);
        leader.slots[40] = stack(Material.SHIELD, 1);
        sharing.start(leader.id);
        assertFalse(swapHands(leader).isCancelled());
        assertStack(Material.SHIELD, 1, leader.slots[0]);
        assertStack(Material.STONE, 10, leader.slots[40]);
        assertFalse(swapHands(leader).isCancelled());
        host.tick.run();
        assertStack(Material.STONE, 10, other.slots[0]);
        assertStack(Material.SHIELD, 1, other.slots[40]);
    }

    @Test void handSwapsOutsideSharingDoNotLockOtherPlayersInventories() {
        leader.slots[0] = stack(Material.STONE, 10);
        assertFalse(swapHands(leader).isCancelled());
        assertFalse(swapHands(other).isCancelled());
        sharing.start(leader.id);
        swapHands(leader);
        sharing.stop();
        assertFalse(swapHands(other).isCancelled());
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(leader.player);
        sharing.click(click);
        verify(click, never()).setCancelled(true);
    }

    @Test void joiningPlayerGetsSharedInventoryAndLaterTheirOwnOriginalBack() {
        leader.slots[0] = stack(Material.STONE, 10);
        sharing.start(leader.id);
        Person joined = addPerson();
        joined.slots[0] = stack(Material.APPLE, 6);
        sharing.playerConnected(joined.player);
        assertStack(Material.STONE, 10, joined.slots[0]);
        sharing.stop();
        assertStack(Material.APPLE, 6, joined.slots[0]);
    }

    @Test void stopRestoresOriginalInventoryAndCancelsPollingRatherThanKeepingSharedCopies() throws Exception {
        leader.slots[0] = stack(Material.STONE, 10);
        other.slots[0] = stack(Material.APPLE, 6);
        other.slots[40] = stack(Material.SHIELD, 1);
        sharing.start(leader.id);
        leader.slots[0] = stack(Material.STONE, 2);
        sharing.stop();
        assertFalse(sharing.active());
        assertStack(Material.STONE, 10, leader.slots[0]);
        assertStack(Material.APPLE, 6, other.slots[0]);
        assertStack(Material.SHIELD, 1, other.slots[40]);
        verify(host.task).cancel();
        assertFalse(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
        try (var files = Files.walk(directory)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().equals("shared-final.inventory")));
        }
    }

    @Test void quitPublishesLastChangesAndRestoresOriginalBeforePlayerSave() {
        leader.slots[0] = stack(Material.STONE, 10);
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        other.slots[0] = stack(Material.STONE, 9);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(other.player);
        sharing.quit(event);
        assertStack(Material.STONE, 9, leader.slots[0]);
        assertStack(Material.APPLE, 6, other.slots[0]);
    }

    @Test void firstDeathDropsTheWholePoolAndFurtherDeathsDropNothing() {
        leader.slots[0] = stack(Material.STONE, 10);
        leader.slots[36] = stack(Material.IRON_BOOTS, 1);
        leader.slots[40] = stack(Material.SHIELD, 1);
        sharing.start(leader.id);
        when(other.player.isDead()).thenReturn(true);
        PlayerDeathEvent first = death(other);
        sharing.died(first);
        verify(first).setKeepInventory(false);
        assertEquals(3, first.getDrops().size());
        assertTrue(first.getDrops().stream().anyMatch(item -> item.getType() == Material.STONE && item.getAmount() == 10));
        for (int slot = 0; slot < 41; slot++) {
            assertNull(leader.slots[slot]);
            assertNull(other.slots[slot]);
        }
        when(leader.player.isDead()).thenReturn(true);
        PlayerDeathEvent second = death(leader);
        second.getDrops().add(stack(Material.DIAMOND, 1));
        sharing.died(second);
        assertTrue(second.getDrops().isEmpty());
        assertTrue(first.getItemsToKeep().isEmpty());
        verify(second).setKeepInventory(false);
    }

    @Test void deathFlushesTheDyingPlayersLastChangeBeforeChoosingDrops() {
        leader.slots[0] = stack(Material.APPLE, 10);
        sharing.start(leader.id);
        other.slots[0] = stack(Material.APPLE, 9);
        when(other.player.isDead()).thenReturn(true);
        PlayerDeathEvent event = death(other);
        sharing.died(event);
        assertEquals(1, event.getDrops().size());
        assertStack(Material.APPLE, 9, event.getDrops().getFirst());
    }

    @Test void heldCursorItemsJoinFirstDeathDropsOnceAndAreCleared() {
        sharing.start(leader.id);
        other.cursor = stack(Material.DIAMOND, 2);
        PlayerDeathEvent event = death(leader);
        sharing.died(event);
        assertEquals(1, event.getDrops().size());
        assertStack(Material.DIAMOND, 2, event.getDrops().getFirst());
        assertNull(other.cursor);
        PlayerDeathEvent later = death(other);
        sharing.died(later);
        assertTrue(later.getDrops().isEmpty());
    }

    @Test void cancelledDeathDoesNotClearSharedItemsOrAddDrops() {
        leader.slots[0] = stack(Material.STONE, 4);
        sharing.start(leader.id);
        PlayerDeathEvent event = death(other);
        when(event.isCancelled()).thenReturn(true);
        sharing.died(event);
        assertStack(Material.STONE, 4, leader.slots[0]);
        assertTrue(event.getDrops().isEmpty());
        verify(event, never()).setKeepInventory(anyBoolean());
    }

    @Test void nextLifeCanDropItsNewPoolAndRespawnReceivesTheEmptyPool() {
        leader.slots[0] = stack(Material.STONE, 4);
        sharing.start(leader.id);
        sharing.died(death(other));
        sharing.playerConnected(other.player);
        assertNull(other.slots[0]);
        leader.slots[2] = stack(Material.DIAMOND, 3);
        host.tick.run();
        PlayerDeathEvent next = death(leader);
        sharing.died(next);
        assertEquals(1, next.getDrops().size());
        assertStack(Material.DIAMOND, 3, next.getDrops().getFirst());
    }

    @Test void deadPlayersOriginalIsRestoredAfterRespawnWhenGameAlreadyStopped() {
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        when(other.player.isDead()).thenReturn(true);
        sharing.stop();
        when(other.player.isDead()).thenReturn(false);
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
        when(event.getPlayer()).thenReturn(other.player);
        sharing.respawn(event);
        host.runNext();
        assertStack(Material.APPLE, 6, other.slots[0]);
    }

    @Test void automaticGameStopIsDetectedAndOriginalInventoriesRestored() {
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        when(game.running()).thenReturn(false);
        host.tick.run();
        assertFalse(sharing.active());
        assertStack(Material.APPLE, 6, other.slots[0]);
    }

    @Test void quittingOnDeathScreenRetainsBackupAndDefersOriginalUntilLivingAgain() {
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        when(other.player.isDead()).thenReturn(true);
        sharing.died(death(other));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(other.player);
        sharing.quit(quit);
        assertNull(other.slots[0], "must not inject originals into the dead inventory");
        assertTrue(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
        sharing.stop();
        sharing.playerConnected(other.player); // Rejoin while still on the death screen.
        assertNull(other.slots[0]);
        assertTrue(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
        when(other.player.isDead()).thenReturn(false);
        sharing.playerConnected(other.player);
        assertStack(Material.APPLE, 6, other.slots[0]);
        assertFalse(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
    }

    @Test void interruptedSessionDeadReconnectDoesNotStopAnotherRunningGame() {
        sharing.start(leader.id);
        SharedInventoryManager recovered = new SharedInventoryManager(game, host);
        when(other.player.isDead()).thenReturn(true);
        assertTrue(recovered.start(leader.id));
        recovered.playerConnected(other.player);
        assertTrue(recovered.active());
        verify(game, never()).stop();
        assertTrue(Files.exists(directory.resolve("pending").resolve(other.id + ".inventory")));
    }

    @Test void repeatedConnectCallbackMustNotUndoUnpublishedInventoryChanges() {
        leader.slots[0] = stack(Material.STONE, 10);
        sharing.start(leader.id);
        other.slots[0] = stack(Material.STONE, 9);
        sharing.playerConnected(other.player);
        host.tick.run();
        assertStack(Material.STONE, 9, leader.slots[0]);
        assertStack(Material.STONE, 9, other.slots[0]);
    }

    @Test void deadOriginalSurvivesStopAndNewSessionWithoutEnteringDeathDrops() {
        leader.slots[0] = stack(Material.STONE, 4);
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        when(other.player.isDead()).thenReturn(true);
        sharing.died(death(other));
        sharing.stop();
        assertTrue(sharing.start(leader.id));
        sharing.playerConnected(other.player);
        assertNull(other.slots[0]);
        when(other.player.isDead()).thenReturn(false);
        sharing.playerConnected(other.player);
        assertStack(Material.STONE, 4, other.slots[0]);
        sharing.stop();
        assertStack(Material.APPLE, 6, other.slots[0]);
    }

    @Test void corruptPendingBackupDoesNotOverwriteInventoryOrDeleteRecoveryFile() throws Exception {
        Path pending = directory.resolve("pending").resolve(other.id + ".inventory");
        Files.createDirectories(pending.getParent());
        Files.write(pending, new byte[] {0, 0, 0, 1, 0, 0, 0, 5, 1});
        other.slots[0] = stack(Material.DIAMOND, 3);
        sharing.playerConnected(other.player);
        assertStack(Material.DIAMOND, 3, other.slots[0]);
        assertTrue(Files.exists(pending));
        assertFalse(sharing.active());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 16777217, Integer.MAX_VALUE})
    void malformedBackupItemLengthIsRejectedWithoutAllocationOrInventoryChanges(int length) throws Exception {
        Path pending = directory.resolve("pending").resolve(other.id + ".inventory");
        Files.createDirectories(pending.getParent());
        try (var output = new java.io.DataOutputStream(Files.newOutputStream(pending))) {
            output.writeInt(1);
            output.writeInt(length);
        }
        other.slots[0] = stack(Material.DIAMOND, 3);
        sharing.playerConnected(other.player);
        assertStack(Material.DIAMOND, 3, other.slots[0]);
        assertTrue(Files.exists(pending));
        verify(host.logger).log(eq(java.util.logging.Level.SEVERE), anyString(), any(Exception.class));
    }

    @Test void delayedJoinAndRespawnCallbacksAfterStopDoNotOverwriteNewItems() {
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        var join = mock(org.bukkit.event.player.PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(other.player);
        sharing.joined(join);
        var respawn = mock(PlayerRespawnEvent.class);
        when(respawn.getPlayer()).thenReturn(other.player);
        sharing.respawn(respawn);
        sharing.stop();
        other.slots[0] = stack(Material.DIAMOND, 3);
        host.runNext();
        assertStack(Material.DIAMOND, 3, other.slots[0]);
        assertFalse(sharing.active());
    }

    @Test void randomizedSharedConsumptionPreservesTotalAndNeverCreatesNegativeStacks() {
        var random = new java.util.Random(20261010);
        for (int iteration = 0; iteration < 500; iteration++) {
            int initial = 1 + random.nextInt(64);
            int remaining = initial;
            ItemStack canonical = stack(Material.APPLE, initial);
            for (int player = 0; player < 4; player++) {
                int consumed = random.nextInt(remaining + 1);
                canonical = SharedInventoryManager.merge(canonical, stack(Material.APPLE, initial),
                        stack(Material.APPLE, initial - consumed));
                remaining -= consumed;
                if (remaining == 0) { assertNull(canonical); }
                else { assertStack(Material.APPLE, remaining, canonical); }
            }
        }
    }

    @Test void sharingNeverStartsIfBackupDirectoryCannotBeWritten() throws Exception {
        other.slots[0] = stack(Material.APPLE, 6);
        Path badDirectory = directory.resolve("ordinary-file");
        Files.writeString(badDirectory, "not a directory");
        host.directory = badDirectory;
        assertFalse(sharing.start(leader.id));
        assertFalse(sharing.active());
        assertStack(Material.APPLE, 6, other.slots[0]);
        assertNull(host.tick);
    }

    @Test void midSessionJoinBackupFailureStopsSharingAndRestoresExistingOriginals() throws Exception {
        leader.slots[0] = stack(Material.STONE, 10);
        other.slots[0] = stack(Material.APPLE, 6);
        sharing.start(leader.id);
        Path badDirectory = directory.resolve("later-file");
        Files.writeString(badDirectory, "not a directory");
        host.directory = badDirectory;
        Person joined = addPerson();
        joined.slots[0] = stack(Material.DIAMOND, 1);
        sharing.playerConnected(joined.player);
        verify(game).stop();
        assertFalse(sharing.active());
        assertStack(Material.APPLE, 6, other.slots[0]);
        assertStack(Material.DIAMOND, 1, joined.slots[0]);
    }

    @Test void slotChangeEventOnlyMarksManagedPlayersAndCannotAffectUnrelatedInventory() {
        leader.slots[0] = stack(Material.STONE, 4);
        sharing.start(leader.id);
        PlayerInventorySlotChangeEvent event = mock(PlayerInventorySlotChangeEvent.class);
        when(event.getPlayer()).thenReturn(other.player);
        other.slots[0] = stack(Material.STONE, 3);
        sharing.slotChanged(event);
        host.tick.run();
        assertStack(Material.STONE, 3, leader.slots[0]);
        sharing.stop();
        leader.slots[0] = stack(Material.STONE, 12);
        sharing.slotChanged(event);
        sharing.flush();
        assertStack(Material.STONE, 12, leader.slots[0]);
    }

    @Test void interruptedEmptyInventorySessionCanBeRecoveredFromPendingFiles() {
        sharing.start(leader.id);
        leader.slots[0] = stack(Material.DIAMOND, 4);
        SharedInventoryManager recovered = new SharedInventoryManager(game, host);
        recovered.playerConnected(leader.player);
        assertNull(leader.slots[0]);
        assertFalse(Files.exists(directory.resolve("pending").resolve(leader.id + ".inventory")));
    }

    @Test void interruptedNonemptyBackupRestoresAllSlotKindsAndCursorThroughTheByteCodec() {
        leader.slots[0] = stack(Material.DIAMOND, 12);
        leader.slots[36] = stack(Material.IRON_BOOTS, 1);
        leader.slots[40] = stack(Material.SHIELD, 1);
        leader.cursor = stack(Material.APPLE, 3);
        sharing.start(leader.id);
        java.util.Arrays.fill(leader.slots, null);
        leader.cursor = null;
        SharedInventoryManager recovered = new SharedInventoryManager(game, host);
        // Tests our disk framing and restoration; actual Minecraft NBT remains a server check.
        try (var items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(any(byte[].class))).thenAnswer(call -> {
                var bytes = java.nio.ByteBuffer.wrap(call.getArgument(0));
                return stack(Material.values()[bytes.getInt()], bytes.getInt());
            });
            recovered.playerConnected(leader.player);
        }
        assertStack(Material.DIAMOND, 12, leader.slots[0]);
        assertStack(Material.IRON_BOOTS, 1, leader.slots[36]);
        assertStack(Material.SHIELD, 1, leader.slots[40]);
        assertStack(Material.APPLE, 3, leader.cursor);
        assertFalse(Files.exists(directory.resolve("pending").resolve(leader.id + ".inventory")));
        leader.slots[0] = stack(Material.DIAMOND, 9);
        recovered.playerConnected(leader.player);
        assertStack(Material.DIAMOND, 9, leader.slots[0]); // Only restore a pending backup once.
    }

    @Test void conflictingReplacementRetainsFirstObservedEdit() {
        ItemStack result = SharedInventoryManager.merge(stack(Material.DIAMOND, 1),
                stack(Material.STONE, 4), stack(Material.APPLE, 2));
        assertStack(Material.DIAMOND, 1, result);
    }

    private Person addPerson() {
        Person person = new Person();
        participants.add(person.id);
        host.people.put(person.id, person);
        return person;
    }

    private PlayerSwapHandItemsEvent swapHands(Person person) {
        PlayerSwapHandItemsEvent event = mock(PlayerSwapHandItemsEvent.class);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(event.getPlayer()).thenReturn(person.player);
        when(event.getMainHandItem()).thenReturn(person.slots[40]);
        when(event.getOffHandItem()).thenReturn(person.slots[0]);
        when(event.isCancelled()).thenAnswer(call -> cancelled.get());
        doAnswer(call -> { cancelled.set(call.getArgument(0)); return null; }).when(event).setCancelled(anyBoolean());
        sharing.swapHands(event);
        // Apply the same slot changes as the server only after an accepted event.
        if (!event.isCancelled()) {
            person.slots[0] = event.getMainHandItem();
            person.slots[40] = event.getOffHandItem();
        }
        return event;
    }

    private PlayerDeathEvent death(Person person) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(person.player);
        when(event.getDrops()).thenReturn(new ArrayList<>());
        when(event.getItemsToKeep()).thenReturn(new ArrayList<>());
        return event;
    }

    private static void assertStack(Material type, int amount, ItemStack stack) {
        assertNotNull(stack);
        assertEquals(type, stack.getType());
        assertEquals(amount, stack.getAmount());
    }

    static ItemStack stack(Material type, int count) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger amount = new AtomicInteger(count);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenAnswer(ignored -> amount.get());
        when(item.isEmpty()).thenAnswer(ignored -> amount.get() <= 0 || type == Material.AIR);
        doAnswer(call -> { amount.set(call.getArgument(0)); return null; }).when(item).setAmount(anyInt());
        when(item.clone()).thenAnswer(ignored -> stack(type, amount.get()));
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack another = call.getArgument(0);
            return another != null && another.getType() == type;
        });
        when(item.getMaxStackSize()).thenReturn(64);
        when(item.serializeAsBytes()).thenAnswer(ignored -> java.nio.ByteBuffer.allocate(8)
                .putInt(type.ordinal()).putInt(amount.get()).array());
        return item;
    }

    static class Person {
        final UUID id = UUID.randomUUID();
        final Player player = mock(Player.class);
        final PlayerInventory inventory = mock(PlayerInventory.class);
        final ItemStack[] slots = new ItemStack[41];
        ItemStack cursor;
        Person() {
            when(player.getUniqueId()).thenReturn(id);
            when(player.getInventory()).thenReturn(inventory);
            when(player.isOnline()).thenReturn(true);
            when(player.getItemOnCursor()).thenAnswer(ignored -> cursor);
            doAnswer(call -> { cursor = call.getArgument(0); return null; }).when(player).setItemOnCursor(any());
            when(inventory.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0)]);
            doAnswer(call -> { slots[call.getArgument(0)] = call.getArgument(1); return null; })
                    .when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        }
    }

    static class FakeHost implements SharedInventoryManager.Host {
        final Map<UUID, Person> people = new LinkedHashMap<>();
        final ArrayList<Runnable> next = new ArrayList<>();
        final BukkitTask task = mock(BukkitTask.class);
        final Logger logger = mock(Logger.class);
        Path directory;
        Runnable tick;
        @Override public Player player(UUID id) { return people.containsKey(id) ? people.get(id).player : null; }
        @Override public Collection<? extends Player> onlinePlayers() { return people.values().stream().map(p -> p.player).toList(); }
        @Override public BukkitTask repeat(Runnable callback) { tick = callback; return task; }
        @Override public void nextTick(Runnable callback) { next.add(callback); }
        @Override public Path directory() { return directory; }
        @Override public Logger logger() { return logger; }
        void runNext() {
            var callbacks = new ArrayList<>(next);
            next.clear();
            callbacks.forEach(Runnable::run);
        }
    }
}
