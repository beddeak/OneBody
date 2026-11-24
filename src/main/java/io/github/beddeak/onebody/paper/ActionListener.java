package io.github.beddeak.onebody.paper;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** MONITOR observes permitted actions; never guesses a jump from coordinates or a use from clicks. */
public final class ActionListener implements Listener {
    private final GameManager game;

    public ActionListener(GameManager game) { this.game = game; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void jump(PlayerJumpEvent event) { action(event.getPlayer(), SyncAction.JUMP); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) { action(event.getPlayer(), SyncAction.BLOCK_BREAK); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (event.canBuild()) { action(event.getPlayer(), SyncAction.BLOCK_PLACE); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent event) {
        // Shared hunger can fill players who have not eaten; food never starts a required action.
        if (!event.getItem().getType().isEdible()) { action(event.getPlayer(), SyncAction.ITEM_USE); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            // Actual successful spawn handles bow/crossbow, throwable potions, pearls, tridents, etc.
            action(player, SyncAction.ITEM_USE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void emptyBucket(PlayerBucketEmptyEvent event) { action(event.getPlayer(), SyncAction.ITEM_USE); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fillBucket(PlayerBucketFillEvent event) { action(event.getPlayer(), SyncAction.ITEM_USE); }

    private void action(Player player, SyncAction action) { game.action(player.getUniqueId(), action); }
}
