package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import io.github.beddeak.onebody.game.GameManager;
import io.github.beddeak.onebody.game.SyncAction;
import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.junit.jupiter.api.Test;

class ActionListenerTest {
    private final GameManager game = mock(GameManager.class);
    private final ActionListener listener = new ActionListener(game);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();

    ActionListenerTest() { when(player.getUniqueId()).thenReturn(id); }

    @Test void allActionHandlersObserveOnlyUncancelledEventsAtMonitor() {
        int handlers = 0;
        for (Method method : ActionListener.class.getDeclaredMethods()) {
            EventHandler handler = method.getAnnotation(EventHandler.class);
            if (handler != null) {
                handlers++;
                assertEquals(EventPriority.MONITOR, handler.priority(), method.getName());
                assertTrue(handler.ignoreCancelled(), method.getName());
            }
        }
        assertEquals(7, handlers);
    }

    @Test void dedicatedPaperJumpEventMapsDirectlyWithoutMovementHeuristics() {
        PlayerJumpEvent event = mock(PlayerJumpEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.jump(event);
        verify(game).action(id, SyncAction.JUMP);
    }

    @Test void deniedBuildCannotCountAsBlockPlace() {
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        when(event.getPlayer()).thenReturn(player);
        listener.placeBlock(event);
        verifyNoInteractions(game);
        when(event.canBuild()).thenReturn(true);
        listener.placeBlock(event);
        verify(game).action(id, SyncAction.BLOCK_PLACE);
    }

    @Test void consumptionAndActualProjectileLaunchBothMapToItemUse() {
        PlayerItemConsumeEvent consume = mock(PlayerItemConsumeEvent.class);
        when(consume.getPlayer()).thenReturn(player);
        ItemStack potion = mock(ItemStack.class);
        Material potionType = mock(Material.class);
        when(consume.getItem()).thenReturn(potion);
        when(potion.getType()).thenReturn(potionType);
        listener.consume(consume);
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        Projectile projectile = mock(Projectile.class);
        when(launch.getEntity()).thenReturn(projectile);
        when(projectile.getShooter()).thenReturn(player);
        listener.launch(launch);
        verify(game, times(2)).action(id, SyncAction.ITEM_USE);
    }

    @Test void finishedFoodConsumptionNeverStartsAnActionOrFallsBackToItemUse() {
        PlayerItemConsumeEvent consume = mock(PlayerItemConsumeEvent.class);
        ItemStack food = mock(ItemStack.class);
        Material foodType = mock(Material.class);
        when(foodType.isEdible()).thenReturn(true);
        when(food.getType()).thenReturn(foodType);
        when(consume.getItem()).thenReturn(food);
        when(consume.getPlayer()).thenReturn(player);
        listener.consume(consume);
        verifyNoInteractions(game);
    }

    @Test void projectileWithoutPlayerShooterDoesNotCountAsUse() {
        ProjectileLaunchEvent launch = mock(ProjectileLaunchEvent.class);
        when(launch.getEntity()).thenReturn(mock(Projectile.class));
        listener.launch(launch);
        verifyNoInteractions(game);
    }

}
