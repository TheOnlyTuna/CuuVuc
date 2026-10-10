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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** GUI giữ UUID và vai trò; quyền giao dịch luôn được kiểm tra lại trong PostgreSQL. */
public final class ContractMenu implements Listener {
    private static final class Holder implements InventoryHolder {
        private final UUID viewer;
        private final List<UUID> entries;
        private final Optional<UUID> selected;
        private final int page;
        private final Inventory inventory;
        private Holder(UUID viewer, List<UUID> entries, Optional<UUID> selected, int page) {
            this.viewer = viewer; this.entries = List.copyOf(entries); this.selected = selected; this.page = page;
            inventory = Bukkit.createInventory(this, 36, Component.text("Cửu Vực • Hợp đồng kiến thiết"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final ContractWorld contracts;
    private final PlayableWorld world;

    public ContractMenu(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, ContractWorld contracts, PlayableWorld world) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.contracts = contracts; this.world = world;
    }

    public void open(Player player, int page) {
        if (!player.hasPermission("cuuvuc.play")) return;
        List<Contracts.Job> jobs = contracts.board().jobs();
        int boundedPage = Math.max(0, Math.min(page, Math.max(0, (jobs.size() - 1) / 18)));
        List<Contracts.Job> visible = jobs.stream().skip(boundedPage * 18L).limit(18).toList();
        Holder holder = new Holder(player.getUniqueId(), visible.stream().map(Contracts.Job::id).toList(), Optional.empty(), boundedPage);
        for (int index = 0; index < visible.size(); index++) {
            Contracts.Job job = visible.get(index);
            holder.inventory.setItem(index, icon(Material.WRITABLE_BOOK, "Sàn động phủ #" + job.slot(), List.of(
                    "Tiền công: " + job.reward() + " Linh Tệ", "Ký quỹ: " + job.escrow(), "Trạng thái: " + status(job.status()),
                    "Hết hạn: " + job.expiresAt(), "Bấm để xem và thao tác")));
        }
        holder.inventory.setItem(27, icon(Material.GOLD_NUGGET, "Thuê xây: 2 Linh Tệ", terms()));
        holder.inventory.setItem(28, icon(Material.GOLD_INGOT, "Thuê xây: 10 Linh Tệ", terms()));
        holder.inventory.setItem(29, icon(Material.GOLD_BLOCK, "Thuê xây: 100 Linh Tệ", terms()));
        holder.inventory.setItem(31, icon(Material.ARROW, "Trang trước", List.of()));
        holder.inventory.setItem(32, icon(Material.ARROW, "Trang sau", List.of()));
        holder.inventory.setItem(35, icon(Material.CLOCK, "Làm mới", List.of("Đồng bộ: " + (contracts.fresh() ? "sẵn sàng" : "đang chờ"))));
        player.openInventory(holder.inventory);
    }

    public void enter(Player player) {
        player.getScheduler().run(plugin, task -> open(player, 0), () -> plugin.getSLF4JLogger().info("contract_menu_player_retired"));
    }

    private static List<String> terms() {
        return List.of("Sàn 3×3 cobblestone tại tâm động phủ, y=100", "Dọn trống vùng trước khi tạo; thời hạn 1 giờ", "Ký quỹ Linh Tệ ngay; phí 0%; vật liệu tự thỏa thuận",
                "Chủ chỉ hủy khi chưa có Builder nhận", "Builder bỏ việc hoặc hết hạn: hoàn tiền bên thuê",
                "Công trình, vật liệu không tự hoàn trả", "Bấm để chấp nhận điều kiện và tạo hợp đồng");
    }

    private void detail(Player player, UUID contract) {
        Contracts.Job job = contracts.board().jobs().stream().filter(candidate -> candidate.id().equals(contract)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Hợp đồng chưa đồng bộ. Làm mới bảng."));
        Holder holder = new Holder(player.getUniqueId(), List.of(), Optional.of(contract), 0);
        holder.inventory.setItem(4, icon(Material.BOOK, "Sàn 3×3 • " + status(job.status()), List.of(
                "X: " + (job.centerX() - 1) + ".." + (job.centerX() + 1) + " | Y: 100 | Z: 7..9",
                "Tiền công: " + job.reward() + " Linh Tệ; ký quỹ: " + job.escrow(), "Hết hạn: " + job.expiresAt(), "Mã: " + job.id())));
        UUID actor = player.getUniqueId();
        if (job.status() == Contracts.Status.OPEN && !job.owner().equals(actor)) holder.inventory.setItem(10, icon(Material.IRON_PICKAXE, "Nhận hợp đồng", terms()));
        holder.inventory.setItem(12, icon(Material.ENDER_PEARL, "Đến công trình", List.of("Chủ: " + job.owner())));
        if (job.builder().filter(actor::equals).isPresent() && job.status() == Contracts.Status.ACTIVE)
            holder.inventory.setItem(14, icon(Material.PAPER, "Gửi nghiệm thu", List.of("Cần đủ 9 cobblestone; khóa quyền thi công", "Chờ bên thuê duyệt trước thời hạn")));
        if (job.owner().equals(actor) && job.status() == Contracts.Status.SUBMITTED)
            holder.inventory.setItem(16, icon(Material.EMERALD, "Nghiệm thu và trả tiền", List.of("Kiểm tra sàn rồi chuyển ký quỹ cho Builder")));
        if (job.owner().equals(actor) && job.status() == Contracts.Status.OPEN)
            holder.inventory.setItem(20, icon(Material.BARRIER, "Hủy và hoàn ký quỹ", List.of("Chỉ áp dụng khi chưa nhận việc")));
        if (job.builder().filter(actor::equals).isPresent() && (job.status() == Contracts.Status.ACTIVE || job.status() == Contracts.Status.SUBMITTED))
            holder.inventory.setItem(22, icon(Material.BARRIER, "Bỏ việc; hoàn tiền bên thuê", List.of("Bạn không nhận tiền công", "Công trình và vật liệu không tự hoàn trả")));
        holder.inventory.setItem(24, icon(Material.WRITTEN_BOOK, "Lịch sử công trình", List.of("20 sự kiện gần nhất trong chat")));
        holder.inventory.setItem(35, icon(Material.ARROW, "Về bảng hợp đồng", List.of()));
        player.openInventory(holder.inventory);
    }

    private static String status(Contracts.Status value) {
        return switch (value) {
            case OPEN -> "Đang tuyển Builder"; case ACTIVE -> "Đang thi công"; case SUBMITTED -> "Chờ nghiệm thu";
            case COMPLETED -> "Đã hoàn thành"; case CANCELLED -> "Đã hủy"; case EXPIRED -> "Đã hết hạn";
        };
    }

    private static ItemStack icon(Material material, String name, List<String> lines) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(lines.stream().map(Component::text).toList()); item.setItemMeta(meta); return item;
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewer.equals(player.getUniqueId()) || !player.hasPermission("cuuvuc.play")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 36 || holder.inventory.getItem(slot) == null) return;
        player.getScheduler().run(plugin, task -> {
            try {
                if (holder.selected.isEmpty()) {
                    if (slot < holder.entries.size()) { detail(player, holder.entries.get(slot)); return; }
                    switch (slot) {
                        case 27 -> create(player, 2);
                        case 28 -> create(player, 10);
                        case 29 -> create(player, 100);
                        case 31 -> open(player, holder.page - 1); case 32 -> open(player, holder.page + 1); case 35 -> open(player, holder.page);
                        default -> { }
                    }
                } else {
                    UUID contract = holder.selected.orElseThrow();
                    Contracts.Job job = contracts.board().jobs().stream().filter(candidate -> candidate.id().equals(contract)).findFirst().orElseThrow();
                    player.closeInventory();
                    switch (slot) {
                        case 10 -> contracts.accept(player, contract); case 12 -> world.visit(player, job.owner());
                        case 14 -> contracts.submit(player, job); case 16 -> contracts.complete(player, job);
                        case 20 -> contracts.cancel(player, contract); case 22 -> contracts.abandon(player, contract);
                        case 24 -> tasks.submit(player, UUID.randomUUID(), () -> database.contractHistory(contract)); case 35 -> open(player, 0);
                        default -> { }
                    }
                }
            } catch (IllegalArgumentException failure) { player.sendPlainMessage(failure.getMessage()); }
        }, () -> plugin.getSLF4JLogger().info("contract_menu_player_retired"));
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private void create(Player player, long reward) {
        Domain.Island island = world.ownerIsland(player.getUniqueId()).orElseThrow(() -> new IllegalArgumentException("Dùng /cv home để tạo động phủ trước."));
        player.closeInventory(); contracts.create(player, reward, island);
    }
}
