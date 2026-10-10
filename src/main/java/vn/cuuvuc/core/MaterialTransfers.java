package vn.cuuvuc.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/** Cổng GUI giao nhận: một cửa sổ là một ý định; PDC không tự cấp quyền hoặc số lượng. */
public final class MaterialTransfers implements Listener {
    private record Intent(UUID request, UUID sender, UUID recipient, String resource, long count, long revision) { }
    private static final class Holder implements InventoryHolder {
        private final Intent intent;
        private final Inventory inventory;
        private Holder(Intent intent) {
            this.intent = intent;
            this.inventory = Bukkit.createInventory(this, 27, Component.text("Cửu Vực • Xác nhận giao nhận"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final PhysicalItems items;
    private final Domain.Catalog catalog;

    public MaterialTransfers(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, PhysicalItems items, Domain.Catalog catalog) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.items = items; this.catalog = catalog;
    }

    public void request(Player player, UUID recipient, String resource, long count) {
        if (player.getUniqueId().equals(recipient)) throw new IllegalArgumentException("Không thể chuyển nguyên liệu cho chính mình.");
        Domain.Resource definition = Domain.resource(catalog, resource);
        UUID sender = player.getUniqueId();
        UUID request = UUID.randomUUID();
        tasks.submitView(player, request, () -> database.view(sender, "Kiểm tra người nhận và số lượng trước khi xác nhận."), view -> {
            if (!items.sync(player, view)) return;
            Holder holder = new Holder(new Intent(request, sender, recipient, resource, count, view.revision()));
            holder.inventory.setItem(11, icon(Material.valueOf(definition.block()), "Gửi " + count + " " + definition.name(),
                    List.of("Kho hiện có: " + view.stock().getOrDefault(resource, 0L), "Người nhận: " + recipient,
                            "Người nhận cần có hồ sơ; có thể đang offline", "Inventory đầy vẫn nhận vào kho DB", "Giao nhận đã commit không tự hoàn trả")));
            holder.inventory.setItem(15, icon(Material.LIME_DYE, "Xác nhận chuyển", List.of("Request: " + request, "Bấm lặp trong cửa sổ này không chuyển hai lần")));
            player.openInventory(holder.inventory);
        });
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(lore.stream().map(Component::text).toList()); item.setItemMeta(meta);
        return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.intent.sender().equals(player.getUniqueId())) return;
        if (!player.hasPermission("cuuvuc.play") || event.getRawSlot() != 15 || !event.isLeftClick()) return;
        Intent intent = holder.intent;
        player.getScheduler().run(plugin, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder || !player.hasPermission("cuuvuc.play")) return;
            tasks.submitView(player, intent.request(), () -> {
                String result = database.transferMaterials(intent.request(), intent.sender(), intent.recipient(), intent.resource(), intent.count(), intent.revision());
                refreshRecipient(intent.recipient(), intent.request());
                return database.view(intent.sender(), result);
            }, view -> {
                items.sync(player, view);
                if (player.getOpenInventory().getTopInventory().getHolder() == holder) player.closeInventory();
            });
        }, () -> plugin.getSLF4JLogger().atInfo().addKeyValue("request", intent.request()).log("material_sender_retired_before_confirmation"));
    }

    /** Tra người nhận trên scheduler toàn cục rồi đồng bộ trên entity; offline giữ tài sản trong DB. */
    private void refreshRecipient(UUID recipient, UUID request) {
        try {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                Player target = Bukkit.getPlayer(recipient);
                if (target == null) return;
                target.getScheduler().run(plugin, entityTask -> tasks.submitView(target, UUID.randomUUID(),
                        () -> database.view(recipient, "Bạn đã nhận nguyên liệu. Giao dịch: " + request), view -> items.sync(target, view)),
                        () -> plugin.getSLF4JLogger().atInfo().addKeyValue("player", recipient).addKeyValue("request", request).log("material_recipient_retired_before_sync"));
            });
        } catch (IllegalPluginAccessException failure) {
            plugin.getSLF4JLogger().atWarn().addKeyValue("player", recipient).addKeyValue("request", request)
                    .setCause(failure).log("material_committed_sync_not_scheduled_during_shutdown");
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
}
