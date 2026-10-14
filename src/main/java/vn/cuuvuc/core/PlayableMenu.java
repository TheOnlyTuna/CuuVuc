package vn.cuuvuc.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

/** Menu được nhận diện bằng holder riêng; tên inventory không cấp quyền hoặc thực hiện giao dịch. */
public final class PlayableMenu implements Listener {
    public static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;
        public Holder(UUID owner) { this.owner = owner; inventory = Bukkit.createInventory(this, 27, Component.text("Cửu Vực • Tu hành & sinh sống")); }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final PhysicalItems items;
    private final Domain.Catalog catalog;
    private final PlayableWorld world;
    private final ContractMenu contracts;
    private final CultivationMenu cultivation;

    public PlayableMenu(GameDatabase database, PlayerTasks tasks, PhysicalItems items, Domain.Catalog catalog, PlayableWorld world, ContractMenu contracts, CultivationMenu cultivation) {
        this.database = database; this.tasks = tasks; this.items = items; this.catalog = catalog; this.world = world; this.contracts = contracts; this.cultivation = cultivation;
    }

    public void request(Player player) {
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return; }
        UUID id = player.getUniqueId();
        tasks.submitView(player, UUID.randomUUID(), () -> database.view(id, "Tu hành và xây dựng thế giới của bạn."), view -> {
            if (items.sync(player, view)) open(player, view);
        });
    }

    public void open(Player player, Domain.PlayerView view) {
        Holder holder = new Holder(player.getUniqueId());
        Inventory inventory = holder.getInventory();
        inventory.setItem(4, icon(Material.NETHER_STAR, Cultivation.title(view.cultivation()), List.of("Tu vi: " + view.xp() + " XP", "Linh tệ: " + view.balance(), "Đủ XP chưa tự tiến tầng; mở Tu Luyện")));
        inventory.setItem(6, icon(Material.ENCHANTED_BOOK, "Tu Luyện • Con Đường Tu Tiên", List.of("Phàm Nhân → Luyện Khí 1–9 → Trúc Cơ", "Điều kiện, thử thách và lợi ích tại động phủ")));
        inventory.setItem(10, icon(Material.IRON_PICKAXE, "Đến Prison", List.of("Khai thác mạch quặng, nhận nguyên liệu")));
        inventory.setItem(12, icon(Material.OAK_SAPLING, "Động phủ của bạn", List.of("Đảo đầu tiên, nơi bắt đầu xây dựng")));
        inventory.setItem(14, icon(Material.CRAFTING_TABLE, "Kết tinh linh thạch", List.of("5 linh thạch thô → 1 linh thạch", "Bấm để chế tạo một lần")));
        inventory.setItem(16, icon(Material.CHEST, "Kho & nhận nguyên liệu", List.of("Bấm để làm mới và nhận khi inventory còn chỗ")));
        inventory.setItem(18, icon(Material.WRITABLE_BOOK, "Hợp đồng kiến thiết", List.of("Thuê xây dựng, ký quỹ và nghiệm thu bằng Linh Tệ")));
        inventory.setItem(19, icon(Material.HOPPER, "Giao nhận nguyên liệu", List.of("/cv transfer <UUID> <resource> <số lượng>", "Mở cửa sổ xác nhận trước khi chuyển", "/cv materials để xem lịch sử")));
        inventory.setItem(8, icon(Material.BEACON, "Tông môn • Khai Sơn Lập Phái", List.of("/cv sect để quản lý thành viên và lời mời", "/cv sect create <tên> để thành lập", "Ngân khố thuộc Phase 3, chưa triển khai")));
        int slot = 20;
        for (Domain.Resource resource : catalog.resources().values().stream().sorted(java.util.Comparator.comparing(Domain.Resource::id)).toList()) {
            inventory.setItem(slot++, icon(Material.valueOf(resource.block()), resource.name(), List.of("Sở hữu: " + view.stock().getOrDefault(resource.id(), 0L), "Chuột trái: bán 1, nhận " + resource.sell(), "Chuột phải: mua 1, trả " + resource.buy())));
        }
        player.openInventory(inventory);
    }

    static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(lore.stream().map(Component::text).toList()); item.setItemMeta(meta);
        return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (!player.hasPermission("cuuvuc.play")) return;
        int slot = event.getRawSlot();
        if (slot == 6 || slot == 4) { cultivation.request(player); return; }
        if (slot == 10) { world.enterPrisonFromMenu(player); return; }
        if (slot == 12) { world.enterHomeFromMenu(player); return; }
        if (slot == 16) { request(player); return; }
        if (slot == 18) { contracts.enter(player); return; }
        if (slot == 19) { player.sendPlainMessage("/cv transfer <UUID-người-nhận> <resource> <1..1000> mở xác nhận; /cv catalog xem resource và UUID; /cv materials xem lịch sử."); return; }
        UUID id = player.getUniqueId(); String name = player.getName(); UUID request = UUID.randomUUID();
        if (slot == 14) {
            tasks.submitView(player, request, () -> database.view(id, database.craft(request, id, name, "ket_tinh", 1)), view -> {
                if (items.sync(player, view)) open(player, view);
            });
        } else if (slot >= 20 && slot < 20 + catalog.resources().size()) {
            String resource = catalog.resources().keySet().stream().sorted().toList().get(slot - 20);
            if (event.isLeftClick()) {
                tasks.submitView(player, request, () -> database.view(id, database.sell(request, id, name, resource, 1)), view -> {
                    if (items.sync(player, view)) open(player, view);
                });
            } else if (event.isRightClick()) {
                tasks.submitView(player, request, () -> database.view(id, database.buy(request, id, name, resource, 1)), view -> {
                    if (items.sync(player, view)) open(player, view);
                });
            }
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true) public void station(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || !event.getAction().isRightClick()) return;
        if (!event.getClickedBlock().getWorld().getName().equals(PlayableWorld.PRISON)) return;
        Material material = event.getClickedBlock().getType();
        if (material == Material.CRAFTING_TABLE || material == Material.BARREL) {
            event.setCancelled(true); request(event.getPlayer());
        }
    }
}
