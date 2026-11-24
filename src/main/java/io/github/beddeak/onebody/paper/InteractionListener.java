package io.github.beddeak.onebody.paper;

import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Switch;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Direct block interactions only; redstone updates and custom inventory menus are not actions. */
public final class InteractionListener implements Listener {
    private final GameManager game;

    public InteractionListener(GameManager game) { this.game = game; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent event) {
        // Interact has independent block/item cancellation and may fire for both hands.
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (event.useInteractedBlock() != Event.Result.ALLOW && player.isSneaking()
                && (!player.getInventory().getItemInMainHand().isEmpty()
                    || !player.getInventory().getItemInOffHand().isEmpty())) {
            return; // Sneaking with an item bypasses the normal block interaction.
        }
        var block = event.getClickedBlock();
        var data = block.getBlockData();
        if ((data instanceof Door || data instanceof TrapDoor || data instanceof Gate)
                && block.getType() != Material.IRON_DOOR && block.getType() != Material.IRON_TRAPDOOR) {
            game.action(player.getUniqueId(), SyncAction.DOOR_USE);
        } else if (data instanceof Switch) {
            game.action(player.getUniqueId(), SyncAction.SWITCH_USE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void open(InventoryOpenEvent event) {
        if (event.isCancelled() || !(event.getPlayer() instanceof Player player)) { return; }
        var holder = event.getInventory().getHolder(false);
        if (holder instanceof Chest || holder instanceof DoubleChest
                || holder instanceof Barrel || holder instanceof ShulkerBox) {
            game.action(player.getUniqueId(), SyncAction.CONTAINER_OPEN);
        }
    }
}
