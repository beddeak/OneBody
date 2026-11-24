package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InteractionListenerTest {
    private final GameManager game = mock(GameManager.class);
    private final InteractionListener listener = new InteractionListener(game);
    private final Player player = mock(Player.class);
    private final Block block = mock(Block.class);
    private final UUID id = UUID.randomUUID();

    InteractionListenerTest() { when(player.getUniqueId()).thenReturn(id); }

    private PlayerInteractEvent event(Class<? extends BlockData> type, Material material) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(block.getBlockData()).thenReturn(mock(type));
        when(block.getType()).thenReturn(material);
        return event;
    }

    @ParameterizedTest @ValueSource(strings = {"door", "trapdoor", "gate"})
    void allManuallyOperatedDoorTypesCountForEitherDirection(String type) {
        var event = switch (type) {
            case "door" -> event(Door.class, Material.OAK_DOOR);
            case "trapdoor" -> event(TrapDoor.class, Material.OAK_TRAPDOOR);
            default -> event(Gate.class, Material.OAK_FENCE_GATE);
        };
        listener.interact(event);
        verify(game).action(id, SyncAction.DOOR_USE);
    }

    @ParameterizedTest @ValueSource(strings = {"LEVER", "STONE_BUTTON", "OAK_BUTTON"})
    void leversAndButtonsShareOneAction(String material) {
        listener.interact(event(Switch.class, Material.valueOf(material)));
        verify(game).action(id, SyncAction.SWITCH_USE);
    }

    @Test void ironDoorsCannotBeCompletedByClickingThem() {
        listener.interact(event(Door.class, Material.IRON_DOOR));
        listener.interact(event(TrapDoor.class, Material.IRON_TRAPDOOR));
        verifyNoInteractions(game);
    }

    @Test void offhandLeftClicksAirAndBlockedInteractionsCannotCount() {
        var event = event(Door.class, Material.OAK_DOOR);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
        listener.interact(event);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
        listener.interact(event);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
        listener.interact(event);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        when(event.getClickedBlock()).thenReturn(null);
        listener.interact(event);
        verifyNoInteractions(game);
    }

    @Test void independentItemDenialMustNotHideAllowedBlockUse() {
        var event = event(Door.class, Material.OAK_DOOR);
        when(event.useItemInHand()).thenReturn(Event.Result.DENY);
        when(event.useInteractedBlock()).thenReturn(Event.Result.ALLOW);
        listener.interact(event);
        verify(game).action(id, SyncAction.DOOR_USE);
    }

    @Test void sneakingWithEitherHandOccupiedBypassesNormalBlockUseButEmptyHandsCanUse() {
        var event = event(Door.class, Material.OAK_DOOR);
        when(player.isSneaking()).thenReturn(true);
        var inventory = mock(PlayerInventory.class);
        var main = mock(ItemStack.class);
        var off = mock(ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(main);
        when(inventory.getItemInOffHand()).thenReturn(off);
        listener.interact(event);
        when(main.isEmpty()).thenReturn(true);
        listener.interact(event);
        verifyNoInteractions(game);
        when(off.isEmpty()).thenReturn(true);
        listener.interact(event);
        verify(game).action(id, SyncAction.DOOR_USE);
        when(main.isEmpty()).thenReturn(false);
        when(event.useInteractedBlock()).thenReturn(Event.Result.ALLOW);
        listener.interact(event);
        verify(game, times(2)).action(id, SyncAction.DOOR_USE);
    }

    @ParameterizedTest @ValueSource(strings = {"chest", "double", "barrel", "shulker"})
    void openingPhysicalStorageCounts(String type) {
        InventoryHolder holder = switch (type) {
            case "chest" -> mock(Chest.class);
            case "double" -> mock(DoubleChest.class);
            case "barrel" -> mock(Barrel.class);
            default -> mock(ShulkerBox.class);
        };
        var event = opening(holder);
        listener.open(event);
        verify(game).action(id, SyncAction.CONTAINER_OPEN);
    }

    private InventoryOpenEvent opening(InventoryHolder holder) {
        var event = mock(InventoryOpenEvent.class);
        var inventory = mock(Inventory.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(inventory.getHolder(false)).thenReturn(holder);
        return event;
    }

    @Test void cancelledOpeningCustomMenusAndUnrelatedBlocksDoNotCount() {
        var event = opening(mock(Chest.class));
        when(event.isCancelled()).thenReturn(true);
        listener.open(event);
        listener.open(opening(null));
        listener.open(opening(mock(InventoryHolder.class)));
        listener.interact(event(BlockData.class, Material.CHEST));
        listener.interact(event(BlockData.class, Material.STONE));
        verifyNoInteractions(game);
    }

    @Test void finalBlockResultAndUncancelledOpensAreObservedAtMonitor() throws Exception {
        EventHandler interact = InteractionListener.class.getMethod("interact", PlayerInteractEvent.class).getAnnotation(EventHandler.class);
        EventHandler open = InteractionListener.class.getMethod("open", InventoryOpenEvent.class).getAnnotation(EventHandler.class);
        assertEquals(EventPriority.MONITOR, interact.priority());
        assertFalse(interact.ignoreCancelled()); // The two independent interact results are checked inside.
        assertEquals(EventPriority.MONITOR, open.priority());
        assertTrue(open.ignoreCancelled());
    }
}
