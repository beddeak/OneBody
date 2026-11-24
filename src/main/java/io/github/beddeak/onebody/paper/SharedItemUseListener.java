package io.github.beddeak.onebody.paper;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Cancel conflicting world actions, not just the durability/stack decrement after they succeeded. */
public final class SharedItemUseListener implements Listener {
    private final SharedInventoryManager inventory;

    public SharedItemUseListener(SharedInventoryManager inventory) { this.inventory = inventory; }

    private boolean hand(Player player, EquipmentSlot hand) {
        int slot = hand == EquipmentSlot.OFF_HAND ? 40 : player.getInventory().getHeldItemSlot();
        ItemStack held = player.getInventory().getItem(slot);
        return held == null || held.isEmpty() || inventory.claimUse(player, slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!event.isCancelled() && !hand(event.getPlayer(), EquipmentSlot.HAND)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (!event.isCancelled() && event.canBuild() && !hand(event.getPlayer(), event.getHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        if (!event.isCancelled() && event.getFinalDamage() > 0 && event.getDamager() instanceof Player player
                && (event.getCause() == DamageCause.ENTITY_ATTACK || event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK)
                && !hand(player, EquipmentSlot.HAND)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        // Bowls/bottles can be returned into a different slot from the consumed item.
        if (!event.isCancelled() && !inventory.claimInventoryUse(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void placeEntity(EntityPlaceEvent event) {
        if (!event.isCancelled() && event.getPlayer() != null && !hand(event.getPlayer(), event.getHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent event) {
        // Includes feeding, taming, equipping and the armor-stand interaction subclass.
        if (!event.isCancelled() && !hand(event.getPlayer(), event.getHand())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void shear(PlayerShearEntityEvent event) {
        if (!event.isCancelled() && !hand(event.getPlayer(), event.getHand())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) {
        if (!event.isCancelled() && event.getPlayer() != null && !inventory.claimInventoryUse(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void emptyBucket(PlayerBucketEmptyEvent event) {
        if (!event.isCancelled() && !inventory.claimInventoryUse(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fillBucket(PlayerBucketFillEvent event) {
        // Bucket replacements can insert into another slot when the source stack has multiple buckets.
        if (!event.isCancelled() && !inventory.claimInventoryUse(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent event) {
        if (!event.isCancelled() && event.getEntity() instanceof Player player
                && !inventory.claimInventoryUse(player)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void load(EntityLoadCrossbowEvent event) {
        if (!event.isCancelled() && event.getEntity() instanceof Player player
                && !inventory.claimInventoryUse(player)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void throwItem(PlayerLaunchProjectileEvent event) {
        if (!event.isCancelled() && !inventory.claimInventoryUse(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        // Also covers player projectiles whose launch has no hand-specific precursor (e.g. tridents).
        if (!event.isCancelled() && event.getEntity().getShooter() instanceof Player player
                && !inventory.claimInventoryUse(player)) { event.setCancelled(true); }
    }
}
