package io.github.beddeak.onebody.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.junit.jupiter.api.Test;

class SharedDurabilityTest {
    @Test void concurrentWearAddsEveryLossWithoutChangingInputs() {
        ItemStack before = item(10), first = item(12), second = item(13);
        assertEquals(15, damage(SharedInventoryManager.merge(first, before, second)));
        assertEquals(10, damage(before));
        assertEquals(12, damage(first));
        assertEquals(13, damage(second));
    }

    @Test void accumulatedWearBreaksAtCustomDurabilityLimit() {
        assertNull(SharedInventoryManager.merge(item(99), item(97), item(98)));
        assertNull(SharedInventoryManager.merge(item(99), item(97), item(99)));
    }

    @Test void staleWearCannotResurrectAnAlreadyBrokenItem() {
        assertNull(SharedInventoryManager.merge(null, item(98), item(99)));
    }

    @Test void aSecondPlayersActualBreakRemovesRemainingCanonicalItem() {
        assertNull(SharedInventoryManager.merge(item(99), item(98), null));
    }

    @Test void mendingFromMultiplePlayersAddsRepairsAndClampsAtZero() {
        assertEquals(3, damage(SharedInventoryManager.merge(item(6), item(10), item(7))));
        assertEquals(0, damage(SharedInventoryManager.merge(item(3), item(10), item(4))));
    }

    @Test void simultaneousWearAndRepairApplyNetChange() {
        assertEquals(9, damage(SharedInventoryManager.merge(item(12), item(10), item(7))));
    }

    @Test void differentItemMetadataRetainsFirstEdit() {
        ItemStack named = item(12, "renamed");
        ItemStack result = SharedInventoryManager.merge(named, item(10), item(13));
        assertTrue(named.isSimilar(result));
        assertEquals(12, damage(result));
    }

    @Test void removedItemCannotBeRestoredByStaleRepair() {
        assertNull(SharedInventoryManager.merge(null, item(10), item(5)));
    }

    @Test void largeDamageDeltaDoesNotOverflowIntoRepairedItem() {
        assertNull(SharedInventoryManager.merge(item(99), item(1), item(Integer.MAX_VALUE)));
    }

    @Test void exhaustedElytraRemainRepairableInsteadOfVanishing() {
        ItemStack result = SharedInventoryManager.merge(item(Material.ELYTRA, 99, "shared"),
                item(Material.ELYTRA, 98, "shared"), item(Material.ELYTRA, 99, "shared"));
        assertNotNull(result);
        assertEquals(Material.ELYTRA, result.getType());
        assertEquals(99, damage(result));
    }

    static int damage(ItemStack item) { return ((Damageable) item.getItemMeta()).getDamage(); }
    static ItemStack item(int damage) { return item(damage, "shared"); }

    // Value-like mocks keep getItemMeta/clone isolated, as Bukkit does. Actual NBT needs a server.
    private static ItemStack item(int damage, String marker) {
        return item(Material.IRON_BOOTS, damage, marker);
    }

    private static ItemStack item(Material type, int damage, String marker) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger current = new AtomicInteger(damage);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenReturn(1);
        when(item.isEmpty()).thenReturn(false);
        when(item.getMaxStackSize()).thenReturn(1);
        when(item.clone()).thenAnswer(call -> item(type, current.get(), marker));
        when(item.getItemMeta()).thenAnswer(call -> meta(current.get(), marker));
        when(item.setItemMeta(any())).thenAnswer(call -> {
            current.set(((Damageable) call.getArgument(0)).getDamage());
            return true;
        });
        when(item.isSimilar(any())).thenAnswer(call -> {
            ItemStack other = call.getArgument(0);
            return other != null && other.getType() == type
                    && other.getItemMeta() instanceof Damageable value
                    && value.getDamage() == current.get() && Component.text(marker).equals(value.itemName());
        });
        when(item.serializeAsBytes()).thenReturn(new byte[] {1});
        return item;
    }

    private static Damageable meta(int damage, String marker) {
        Damageable meta = mock(Damageable.class);
        AtomicInteger value = new AtomicInteger(damage);
        when(meta.getDamage()).thenAnswer(call -> value.get());
        doAnswer(call -> { value.set(call.getArgument(0)); return null; }).when(meta).setDamage(anyInt());
        when(meta.hasMaxDamage()).thenReturn(true);
        when(meta.getMaxDamage()).thenReturn(100);
        when(meta.itemName()).thenReturn(Component.text(marker));
        return meta;
    }
}
