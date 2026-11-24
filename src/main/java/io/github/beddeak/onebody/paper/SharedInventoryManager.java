package io.github.beddeak.onebody.paper;

import io.github.beddeak.onebody.game.GameManager;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread shared storage, armour and offhand with durable pre-session originals. */
public final class SharedInventoryManager implements Listener {
    private static final int INVENTORY_SIZE = 41;
    private static final int BACKUP_FORMAT = 1;
    private final GameManager game;
    private final Host host;
    private final Map<UUID, Snapshot> originals = new LinkedHashMap<>();
    private final Map<UUID, Snapshot> mirrors = new LinkedHashMap<>();
    private final Set<UUID> dirty = new LinkedHashSet<>();
    private final Map<Integer, UUID> useOwners = new LinkedHashMap<>();
    private ItemStack[] shared;
    private UUID session;
    private UUID editor;
    private BukkitTask task;
    private boolean applying;
    private boolean active;

    public SharedInventoryManager(JavaPlugin plugin, GameManager game) {
        this(game, new Host() {
            @Override public Player player(UUID id) { return plugin.getServer().getPlayer(id); }
            @Override public Collection<? extends Player> onlinePlayers() { return plugin.getServer().getOnlinePlayers(); }
            @Override public BukkitTask repeat(Runnable callback) {
                return plugin.getServer().getScheduler().runTaskTimer(plugin, callback, 1L, 1L);
            }
            @Override public void nextTick(Runnable callback) {
                plugin.getServer().getScheduler().runTask(plugin, callback);
            }
            @Override public Path directory() { return plugin.getDataFolder().toPath().resolve("shared-inventory-backup"); }
            @Override public Logger logger() { return plugin.getLogger(); }
        });
    }

    SharedInventoryManager(GameManager game, Host host) { this.game = game; this.host = host; }

    /** Call after GameManager.start(); the first eligible participant supplies the initial shared contents. */
    public boolean start(UUID leader) {
        stop();
        if (!game.running() || !game.eligibleParticipants().contains(leader)) { return false; }
        Player source = host.player(leader);
        if (source == null || source.isDead()) { return false; }
        session = UUID.randomUUID();
        // Close screens before backing up so their unique cursor/crafting items return normally.
        for (UUID id : game.participants()) {
            Player player = host.player(id);
            if (player == null || player.isDead()) { continue; }
            if (!restorePending(player)) { return false; }
            player.closeInventory();
        }
        shared = capture(source).contents();
        for (UUID id : game.participants()) {
            Player player = host.player(id);
            if (player == null || player.isDead()) { continue; }
            if (!remember(player)) {
                stop();
                return false;
            }
        }
        active = true;
        for (UUID id : mirrors.keySet()) { applyShared(host.player(id)); }
        task = host.repeat(this::tick);
        return true;
    }

    /** Also call for already-online players on enable to recover an interrupted session. */
    public void playerConnected(Player player) {
        // A death-screen rejoin is not a safe restoration point. Keep both copies of the backup.
        if (!player.isOnline() || player.isDead()) { return; }
        UUID id = player.getUniqueId();
        if (originals.containsKey(id)) {
            if (active && game.running() && game.participants().contains(id)) {
                // A repeated callback may arrive before the next inventory heartbeat.
                // Publish current changes before replacing any physical mirror.
                flush();
                applyShared(player);
            }
            else { restoreOriginal(player); }
            return;
        }
        if (!restorePending(player)) {
            if (active) { game.stop(); stop(); }
            return;
        }
        if (!active || !game.running() || !game.participants().contains(id) || player.isDead()) { return; }
        player.closeInventory();
        if (remember(player)) {
            applyShared(player);
        } else {
            game.stop();
            stop();
        }
    }

    public boolean active() { return active; }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        if (active) {
            for (UUID id : mirrors.keySet()) {
                Player player = host.player(id);
                if (player != null && !player.isDead()) { player.closeInventory(); }
            }
            flush();
            try {
                write(host.directory().resolve(session.toString()).resolve("shared-final.inventory"),
                        new Snapshot(shared, null));
            } catch (IOException | RuntimeException exception) { log("Could not archive final shared inventory", exception); }
        }
        active = false;
        applying = true;
        try {
            for (UUID id : Set.copyOf(originals.keySet())) {
                Player player = host.player(id);
                if (player != null && !player.isDead()) { restoreOriginal(player); }
            }
        } finally { applying = false; }
        mirrors.clear();
        dirty.clear();
        shared = null;
        editor = null;
        useOwners.clear();
    }

    public void shutdown() { stop(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        host.nextTick(() -> { if (player.isOnline()) { playerConnected(player); } });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!originals.containsKey(player.getUniqueId())) { return; }
        if (!player.isDead()) { player.closeInventory(); }
        if (active) { flush(); }
        restoreOriginal(player);
        mirrors.remove(player.getUniqueId());
        dirty.remove(player.getUniqueId());
        if (player.getUniqueId().equals(editor)) { editor = null; }
        useOwners.values().removeIf(player.getUniqueId()::equals);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void died(PlayerDeathEvent event) {
        if (event.isCancelled() || !managed(event.getEntity())) { return; }
        List<ItemStack> cursors = new ArrayList<>();
        for (UUID id : Set.copyOf(mirrors.keySet())) {
            Player player = host.player(id);
            if (player != null) {
                ItemStack cursor = copy(player.getItemOnCursor());
                if (cursor != null) { cursors.add(cursor); player.setItemOnCursor(null); }
                player.closeInventory();
            }
        }
        flush(event.getEntity().getUniqueId());
        event.getDrops().clear();
        for (ItemStack item : shared) {
            if (!empty(item)) { event.getDrops().add(copy(item)); }
        }
        event.getDrops().addAll(cursors);
        event.setKeepInventory(false);
        event.getItemsToKeep().clear();
        // First death consumes the pool; later group deaths cannot drop its physical mirrors again.
        shared = new ItemStack[INVENTORY_SIZE];
        boolean beforeApplying = applying;
        applying = true;
        try {
            for (UUID id : Set.copyOf(mirrors.keySet())) {
                Player player = host.player(id);
                if (player != null) { applyContents(player.getInventory(), shared); }
                mirrors.put(id, new Snapshot(shared, null));
            }
        } finally { applying = beforeApplying; }
        dirty.clear();
        editor = null;
        useOwners.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        host.nextTick(() -> {
            if (!player.isOnline()) { return; }
            if (active && game.running()) { playerConnected(player); }
            else if (originals.containsKey(player.getUniqueId())) { restoreOriginal(player); }
            else { restorePending(player); }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void slotChanged(PlayerInventorySlotChangeEvent event) {
        if (!applying && managed(event.getPlayer())) { dirty.add(event.getPlayer().getUniqueId()); }
    }

    // Permit one user's transaction per tick, keeping ownership while their unique cursor holds an item.
    // This prevents two physical mirrors moving the same shared stack into separate containers.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && !claim(player)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !claim(player)) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swapHands(PlayerSwapHandItemsEvent event) {
        // F moves a shared stack between slots, so it must serialize with GUI/cursor edits too.
        if (!claim(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        if (!claim(event.getPlayer())) { event.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && !claim(player)) { event.setCancelled(true); }
    }

    private boolean claim(Player player) {
        if (!managed(player)) { return true; }
        UUID id = player.getUniqueId();
        if (editor != null && !editor.equals(id)) { return false; }
        if (useOwners.values().stream().anyMatch(owner -> !owner.equals(id))) { return false; }
        editor = id;
        dirty.add(id);
        return true;
    }

    /** Reserve before the action changes the world; publish the owner's changes before releasing. */
    boolean claimUse(Player player, int... slots) {
        if (!managed(player)) { return true; }
        UUID id = player.getUniqueId();
        if (editor != null && !editor.equals(id)) { return false; }
        for (int slot : slots) {
            UUID owner = useOwners.get(slot);
            if (slot < 0 || slot >= INVENTORY_SIZE || (owner != null && !owner.equals(id))) { return false; }
        }
        for (int slot : slots) { useOwners.put(slot, id); }
        dirty.add(id);
        return true;
    }

    /** Bows/crossbows may draw ammunition from another slot, so reserve the complete inventory. */
    boolean claimInventoryUse(Player player) {
        return claimUse(player, java.util.stream.IntStream.range(0, INVENTORY_SIZE).toArray());
    }

    private boolean managed(Player player) {
        return active && game.running() && mirrors.containsKey(player.getUniqueId());
    }

    private void tick() {
        if (!game.running()) { stop(); return; }
        flush();
        useOwners.clear();
        Player owner = editor == null ? null : host.player(editor);
        if (owner == null || owner.isDead() || empty(owner.getItemOnCursor())) { editor = null; }
    }

    /** Polling catches slot changes from durability, commands and other server paths as well as events. */
    void flush() {
        flush(null);
    }

    private void flush(UUID dyingPlayer) {
        if (!active || shared == null || applying) { return; }
        Set<UUID> order = new LinkedHashSet<>(dirty);
        order.addAll(mirrors.keySet());
        for (UUID id : order) {
            Player player = host.player(id);
            Snapshot baseline = mirrors.get(id);
            if (player == null || (player.isDead() && !id.equals(dyingPlayer)) || baseline == null) { continue; }
            ItemStack[] before = baseline.contents();
            ItemStack[] current = capture(player).contents();
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                if (same(before[slot], current[slot])) { continue; }
                shared[slot] = merge(shared[slot], before[slot], current[slot]);
            }
        }
        dirty.clear();
        for (UUID id : Set.copyOf(mirrors.keySet())) { applyShared(host.player(id)); }
    }

    /** Independent count changes merge; conflicting replacements retain the first observed edit. */
    static ItemStack merge(ItemStack canonical, ItemStack before, ItemStack after) {
        if (same(canonical, before)) { return copy(after); }
        if (!empty(before) && before.getAmount() == 1 && empty(after) && !empty(canonical)
                && canonical.getAmount() == 1 && sameExceptDamage(before, canonical)) {
            // A real break/removal must win over another player's smaller wear/repair update.
            return null;
        }
        if (!empty(before) && before.getAmount() == 1 && !empty(after) && after.getAmount() == 1
                && before.getItemMeta() instanceof Damageable oldDamage
                && after.getItemMeta() instanceof Damageable newDamage
                && sameExceptDamage(before, after)) {
            // Passive wear (armour, gliding) and mending must not disappear when players change together.
            // A tool already broken/removed by the first edit must never be resurrected by a stale mirror.
            if (empty(canonical)) { return null; }
            if (canonical.getAmount() == 1 && sameExceptDamage(before, canonical)
                    && canonical.getItemMeta() instanceof Damageable currentDamage) {
                long damage = Math.max(0L, (long) currentDamage.getDamage()
                        + newDamage.getDamage() - oldDamage.getDamage());
                int maximum = currentDamage.hasMaxDamage() ? currentDamage.getMaxDamage()
                        : canonical.getType().getMaxDurability();
                if (maximum > 0 && canonical.getType() == Material.ELYTRA) {
                    // Vanilla elytra become unusable at max - 1; they do not vanish like tools.
                    damage = Math.min(damage, maximum - 1L);
                } else if (maximum > 0 && damage >= maximum) { return null; }
                ItemStack result = copy(canonical);
                currentDamage.setDamage((int) Math.min(Integer.MAX_VALUE, damage));
                result.setItemMeta(currentDamage);
                return result;
            }
        }
        ItemStack kind = empty(before) ? after : before;
        if (!empty(kind) && (empty(before) || kind.isSimilar(before))
                && (empty(after) || kind.isSimilar(after))
                && (empty(canonical) || kind.isSimilar(canonical))) {
            int amount = amount(canonical) + amount(after) - amount(before);
            if (amount <= 0) { return null; }
            ItemStack merged = copy(kind);
            merged.setAmount(Math.min(amount, merged.getMaxStackSize()));
            return merged;
        }
        return copy(canonical);
    }

    private static boolean sameExceptDamage(ItemStack first, ItemStack second) {
        if (!(first.getItemMeta() instanceof Damageable) || !(second.getItemMeta() instanceof Damageable)) {
            return false;
        }
        ItemStack left = first.clone(), right = second.clone();
        Damageable leftMeta = (Damageable) left.getItemMeta(), rightMeta = (Damageable) right.getItemMeta();
        leftMeta.setDamage(0);
        rightMeta.setDamage(0);
        left.setItemMeta(leftMeta);
        right.setItemMeta(rightMeta);
        return left.isSimilar(right);
    }

    private boolean remember(Player player) {
        UUID id = player.getUniqueId();
        if (originals.containsKey(id)) { return true; }
        Snapshot original = capture(player);
        try {
            write(host.directory().resolve(session.toString()).resolve(id + ".inventory"), original);
            write(pending(id), original);
        } catch (IOException | RuntimeException exception) {
            log("Shared inventory not applied: failed to back up " + id, exception);
            return false;
        }
        originals.put(id, original);
        mirrors.put(id, new Snapshot(shared, null));
        return true;
    }

    private void applyShared(Player player) {
        if (player == null || player.isDead() || shared == null) { return; }
        boolean previous = applying;
        applying = true;
        try {
            applyContents(player.getInventory(), shared);
            mirrors.put(player.getUniqueId(), new Snapshot(shared, null));
        } finally { applying = previous; }
    }

    private void restoreOriginal(Player player) {
        if (player.isDead()) { return; }
        Snapshot original = originals.get(player.getUniqueId());
        if (original == null) { return; }
        applySnapshot(player, original);
        originals.remove(player.getUniqueId());
        clearPending(player.getUniqueId());
    }

    private boolean restorePending(Player player) {
        Path path = pending(player.getUniqueId());
        if (!Files.exists(path)) { return true; }
        if (player.isDead()) { return false; }
        try {
            Snapshot original = read(path);
            player.closeInventory();
            applySnapshot(player, original);
            clearPending(player.getUniqueId());
            host.logger().info("Restored OneBody pre-session inventory for " + player.getUniqueId());
            return true;
        } catch (IOException | RuntimeException exception) {
            log("Could not recover original inventory for " + player.getUniqueId(), exception);
            return false;
        }
    }

    private void applySnapshot(Player player, Snapshot snapshot) {
        boolean previous = applying;
        applying = true;
        try {
            player.setItemOnCursor(copy(snapshot.cursor()));
            applyContents(player.getInventory(), snapshot.contents());
        } finally { applying = previous; }
    }

    private Path pending(UUID id) { return host.directory().resolve("pending").resolve(id + ".inventory"); }

    private void clearPending(UUID id) {
        try { Files.deleteIfExists(pending(id)); }
        catch (IOException exception) { log("Inventory restored, but could not delete pending backup for " + id, exception); }
    }

    private void log(String message, Exception exception) { host.logger().log(Level.SEVERE, message, exception); }

    static Snapshot capture(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = new ItemStack[INVENTORY_SIZE];
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) { contents[slot] = copy(inventory.getItem(slot)); }
        return new Snapshot(contents, copy(player.getItemOnCursor()));
    }

    private static void applyContents(PlayerInventory inventory, ItemStack[] contents) {
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (!same(inventory.getItem(slot), contents[slot])) { inventory.setItem(slot, copy(contents[slot])); }
        }
    }

    private static void write(Path path, Snapshot snapshot) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
            output.writeInt(BACKUP_FORMAT);
            for (ItemStack item : snapshot.contents()) { writeItem(output, item); }
            writeItem(output, snapshot.cursor());
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Snapshot read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != BACKUP_FORMAT) { throw new IOException("Unknown inventory backup format"); }
            ItemStack[] contents = new ItemStack[INVENTORY_SIZE];
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) { contents[slot] = readItem(input); }
            return new Snapshot(contents, readItem(input));
        }
    }

    private static void writeItem(DataOutputStream output, ItemStack item) throws IOException {
        byte[] data = empty(item) ? new byte[0] : item.serializeAsBytes();
        output.writeInt(data.length);
        output.write(data);
    }

    private static ItemStack readItem(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) { throw new IOException("Invalid item backup length"); }
        if (length == 0) { return null; }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) { throw new IOException("Truncated inventory backup"); }
        return ItemStack.deserializeBytes(bytes);
    }

    private static boolean empty(ItemStack item) { return item == null || item.getAmount() <= 0 || item.isEmpty(); }
    private static int amount(ItemStack item) { return empty(item) ? 0 : item.getAmount(); }
    private static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
    private static boolean same(ItemStack first, ItemStack second) {
        return empty(first) ? empty(second)
                : !empty(second) && first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    record Snapshot(ItemStack[] contents, ItemStack cursor) {
        Snapshot {
            contents = Arrays.stream(contents).map(SharedInventoryManager::copy).toArray(ItemStack[]::new);
            cursor = copy(cursor);
        }
        @Override public ItemStack[] contents() {
            return Arrays.stream(contents).map(SharedInventoryManager::copy).toArray(ItemStack[]::new);
        }
        @Override public ItemStack cursor() { return copy(cursor); }
    }

    interface Host {
        Player player(UUID id);
        Collection<? extends Player> onlinePlayers();
        BukkitTask repeat(Runnable callback);
        void nextTick(Runnable callback);
        Path directory();
        Logger logger();
    }
}
