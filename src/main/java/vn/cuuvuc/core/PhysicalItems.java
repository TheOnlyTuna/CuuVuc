package vn.cuuvuc.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inventory là bản thể hiện token DB, không phải nguồn tạo tiền. Vật phẩm gắn chủ,
 * không chuyển vào container/drop/craft vanilla; thiếu chỗ vẫn giữ nguyên quyền sở hữu.
 * Mỗi lần đồng bộ loại bản sao rồi dựng tối đa 36 vật phẩm; revision chặn phản hồi cũ.
 */
public final class PhysicalItems implements Listener {
    private final Domain.Catalog catalog;
    private final NamespacedKey tokenKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey resourceKey;
    private final Map<UUID, Long> revisions = new ConcurrentHashMap<>();

    public PhysicalItems(JavaPlugin plugin, Domain.Catalog catalog) {
        this.catalog = catalog;
        tokenKey = new NamespacedKey(plugin, "token");
        ownerKey = new NamespacedKey(plugin, "owner");
        resourceKey = new NamespacedKey(plugin, "resource");
    }

    public boolean tagged(ItemStack item) {
        return item != null && item.getPersistentDataContainer().has(tokenKey, PersistentDataType.STRING);
    }

    public ItemStack create(Domain.ItemToken token) {
        Domain.Resource resource = Domain.resource(catalog, token.resource());
        ItemStack item = new ItemStack(Material.valueOf(resource.block()), 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(resource.name()));
        meta.lore(List.of(Component.text("Nguyên liệu Cửu Vực • Gắn với chủ sở hữu"), Component.text("Dùng tại bàn chế tạo hoặc thương nhân Cửu Vực")));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token.id().toString());
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, token.owner().toString());
        meta.getPersistentDataContainer().set(resourceKey, PersistentDataType.STRING, token.resource());
        item.setItemMeta(meta);
        return item;
    }

    public boolean sync(Player player, Domain.PlayerView view) {
        if (!player.getUniqueId().equals(view.player())) throw new IllegalArgumentException("Snapshot không thuộc người nhận.");
        if (view.revision() < revisions.getOrDefault(view.player(), -1L)) return false;
        revisions.put(view.player(), view.revision());
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (tagged(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, null);
        }
        if (tagged(player.getItemOnCursor())) player.setItemOnCursor(new ItemStack(Material.AIR));
        int delivered = 0;
        for (Domain.ItemToken token : view.items()) {
            int slot = player.getInventory().firstEmpty();
            if (slot < 0 || slot >= 36) break;
            player.getInventory().setItem(slot, create(token));
            delivered++;
        }
        long total = view.stock().values().stream().mapToLong(Long::longValue).reduce(0, Math::addExact);
        if (total > delivered) player.sendPlainMessage("Kho còn " + (total - delivered) + " nguyên liệu chờ nhận. Mở menu kho khi có chỗ trống.");
        return true;
    }

    @EventHandler public void quit(PlayerQuitEvent event) { revisions.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true) public void drop(PlayerDropItemEvent event) {
        if (tagged(event.getItemDrop().getItemStack())) { event.setCancelled(true); event.getPlayer().sendPlainMessage("Nguyên liệu gắn với bạn; dùng menu để chế tạo/bán."); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void use(PlayerInteractEvent event) {
        if (tagged(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) {
        if (tagged(event.getMainHandItem()) || tagged(event.getOffHandItem())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void click(InventoryClickEvent event) {
        boolean hotbar = event.getHotbarButton() >= 0 && tagged(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()));
        if (tagged(event.getCurrentItem()) || tagged(event.getCursor()) || hotbar) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void drag(InventoryDragEvent event) {
        if (tagged(event.getOldCursor())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void hopper(InventoryMoveItemEvent event) {
        if (tagged(event.getItem())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void pickup(EntityPickupItemEvent event) {
        if (tagged(event.getItem().getItemStack())) event.setCancelled(true);
    }
    @EventHandler public void craft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (tagged(item)) { event.getInventory().setResult(null); return; }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void death(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::tagged);
        event.getItemsToKeep().removeIf(this::tagged);
    }
}
