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
import java.util.concurrent.atomic.AtomicBoolean;

/** GUI kho dự án: ý định một lần, DB quyết định tài sản, role và dự trữ. */
public final class ProjectMenu implements Listener {
    private sealed interface Action permits Create, Contribute, Hire { }
    private record Create(UUID sect, String name) implements Action { }
    private record Contribute(UUID project, String resource, long amount, long revision) implements Action { }
    private record Hire(Projects.Project project, Projects.Stage stage, long reward) implements Action { }
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final UUID request;
        private final Optional<Action> action;
        private final Optional<Projects.View> view;
        private final List<Projects.Project> projects;
        private final AtomicBoolean consumed = new AtomicBoolean(false);
        private final Inventory inventory;
        private Holder(UUID owner, UUID request, Optional<Action> action, Optional<Projects.View> view, List<Projects.Project> projects) {
            this.owner = owner; this.request = request; this.action = action; this.view = view; this.projects = List.copyOf(projects);
            inventory = Bukkit.createInventory(this, action.isPresent() ? 27 : 54, Component.text("Cửu Vực • Kho dự án"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final PhysicalItems items;
    private final ContractWorld contracts;
    public ProjectMenu(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, PhysicalItems items, ContractWorld contracts) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.items = items; this.contracts = contracts;
    }
    public void command(Player player, String[] arguments) {
        UUID actor = player.getUniqueId();
        if (arguments.length == 1) { list(player); return; }
        switch (arguments[1]) {
            case "view" -> {
                if (arguments.length != 3) throw usage();
                request(player, UUID.fromString(arguments[2]));
            }
            case "create" -> {
                if (arguments.length < 3) throw usage();
                String name = String.join(" ", List.of(arguments).subList(2, arguments.length));
                tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.treasuryView(actor),
                        view -> confirm(player, new Create(view.sect(), name), List.of("Giao hai vùng trong động phủ của bạn", "Một dự án mỗi động phủ; chưa giải tán", "Kho PDC và block vanilla là hai phần riêng")), view -> "Kiểm tra đất và tên trước khi thành lập dự án.");
            }
            case "contribute" -> {
                if (arguments.length != 5) throw usage();
                contribute(player, UUID.fromString(arguments[2]), arguments[3], Domain.quantity(arguments[4]));
            }
            case "hire" -> {
                if (arguments.length != 5) throw usage();
                UUID project = UUID.fromString(arguments[2]); UUID stage = UUID.fromString(arguments[3]); long reward = Domain.quantity(arguments[4]);
                tasks.submitSnapshot(player, UUID.randomUUID(), () -> {
                    Projects.View view = database.projectView(actor, project);
                    Projects.Stage target = view.stages().stream().filter(value -> value.id().equals(stage)).findFirst().orElseThrow(() -> new IllegalArgumentException("Hạng mục không thuộc dự án."));
                    return new Hire(view.project(), target, reward);
                }, intent -> hire(player, intent.project(), intent.stage(), intent.reward()), intent -> "Kiểm tra dự trữ và tiền công trước khi thuê.");
            }
            default -> throw usage();
        }
    }
    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("/cv projects | create <tên> | view <project> | contribute <project> <resource> <1..1000> | hire <project> <stage> <1..1000>");
    }
    private void list(Player player) {
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.projects(actor), projects -> {
            Holder holder = new Holder(actor, UUID.randomUUID(), Optional.empty(), Optional.empty(), projects);
            for (int index = 0; index < Math.min(45, projects.size()); index++) holder.inventory.setItem(index, icon(Material.BOOK, projects.get(index).name(), List.of("Dự án: " + projects.get(index).id(), "Bấm xem kho và hai hạng mục")));
            holder.inventory.setItem(49, icon(Material.CLOCK, "Làm mới", List.of("/cv projects create <tên>", "Tối đa 45 dự án hiển thị; dùng view <UUID>")));
            player.openInventory(holder.inventory);
        }, projects -> "Dự án tông môn: " + projects.size());
    }
    public void request(Player player, UUID project) {
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.projectView(actor, project), view -> {
            Holder holder = new Holder(actor, UUID.randomUUID(), Optional.empty(), Optional.of(view), List.of());
            holder.inventory.setItem(4, icon(Material.CHEST, view.project().name(), List.of("PROJECT: " + project, "Kho DB: " + view.stock(), "Góp không rút lại; chưa đồng nghĩa đã xây", "Revision: " + view.project().revision())));
            for (int index = 0; index < view.stages().size(); index++) {
                Projects.Stage stage = view.stages().get(index); BuildingTemplates.Template template = BuildingTemplates.get(stage.template());
                holder.inventory.setItem(index == 0 ? 11 : 15, icon(Material.BRICKS, stage.template() + " • " + stage.status(), List.of(
                        "Hạng mục: " + stage.id(), "Định mức: " + stage.costs(), "Dự trữ: " + stage.reserved(),
                        "Vanilla: " + template.cells(view.project().slot()).size() + " " + template.material(),
                        "X tâm: " + (view.project().slot() * 64 + template.offsetX()) + "; Z tâm: " + template.centerZ() + "; Y=100",
                        "READY: bấm thuê 2 Linh Tệ; /cv contracts để xử lý")));
            }
            holder.inventory.setItem(27, icon(Material.AMETHYST_BLOCK, "Góp 1 Linh Thạch", List.of("Kho cá nhân → PROJECT; chưa tiêu thụ")));
            holder.inventory.setItem(28, icon(Material.IRON_ORE, "Góp 1 Huyền Thiết", List.of("Kho cá nhân → PROJECT")));
            holder.inventory.setItem(29, icon(Material.STONE, "Góp 1 Linh Thạch Thô", List.of("Kho được nhận; template hiện tại không dùng loại này")));
            holder.inventory.setItem(40, icon(Material.WRITTEN_BOOK, "10 sự kiện dự án gần nhất", view.history()));
            holder.inventory.setItem(49, icon(Material.CLOCK, "Làm mới", List.of())); player.openInventory(holder.inventory);
        }, view -> "Kho dự án đã tải từ DB.");
    }
    private void contribute(Player player, UUID project, String resource, long count) {
        UUID actor = player.getUniqueId();
        tasks.submitView(player, UUID.randomUUID(), () -> database.view(actor, "Kiểm tra đóng góp không rút lại."), view -> {
            if (!items.sync(player, view)) return;
            confirm(player, new Contribute(project, resource, count, view.revision()), List.of("Dự án: " + project, "Góp " + count + " " + resource, "Kho cá nhân: " + view.stock().getOrDefault(resource, 0L), "Không rút lại đóng góp; không tự đặt block"));
        });
    }
    private void hire(Player player, Projects.Project project, Projects.Stage stage, long reward) {
        confirm(player, new Hire(project, stage, reward), List.of(stage.template(), "Dự trữ tu tiên: " + stage.costs(), "Ký quỹ " + reward + " Linh Tệ từ SECT; phí 0%", "Cần nguyên liệu và tiền trong cùng transaction", "Dọn trống vùng template; Builder tự đặt block vanilla", "Hủy hợp lệ: hoàn dự trữ và tiền về nguồn"));
    }
    private void confirm(Player player, Action action, List<String> description) {
        Holder holder = new Holder(player.getUniqueId(), UUID.randomUUID(), Optional.of(action), Optional.empty(), List.of());
        holder.inventory.setItem(11, icon(Material.PAPER, "Kiểm tra ý định", description));
        holder.inventory.setItem(15, icon(Material.LIME_DYE, "Xác nhận một lần", List.of("Quyền/kho/dự trữ kiểm tra tại DB", "Đóng cửa sổ để bỏ ý định"))); player.openInventory(holder.inventory);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !event.isLeftClick()) return;
        int slot = event.getRawSlot();
        player.getScheduler().run(plugin, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder || !player.hasPermission("cuuvuc.play")) return;
            if (holder.action.isPresent()) {
                if (slot != 15 || !holder.consumed.compareAndSet(false, true)) return;
                holder.inventory.setItem(15, icon(Material.GRAY_DYE, "Ý định đã gửi", List.of("Xem receipt trong chat; không gửi thêm")));
                Action action = holder.action.orElseThrow();
                switch (action) {
                    case Create value -> tasks.submit(player, holder.request, () -> database.createProject(holder.request, holder.owner, value.sect(), value.name()));
                    case Contribute value -> tasks.submitView(player, holder.request, () -> database.view(holder.owner,
                            database.contributeProject(holder.request, holder.owner, value.project(), value.resource(), value.amount(), value.revision())), view -> items.sync(player, view));
                    case Hire value -> { player.closeInventory(); contracts.createStage(player, holder.request, value.project(), value.stage(), value.reward()); }
                }
                return;
            }
            if (holder.view.isEmpty()) {
                if (slot >= 0 && slot < Math.min(45, holder.projects.size())) request(player, holder.projects.get(slot).id());
                else if (slot == 49) list(player);
                return;
            }
            Projects.View view = holder.view.orElseThrow(); UUID project = view.project().id();
            switch (slot) {
                case 11, 15 -> { Projects.Stage stage = view.stages().get(slot == 11 ? 0 : 1); if (stage.status().equals("READY")) hire(player, view.project(), stage, 2); else player.sendPlainMessage("Dùng /cv contracts để xem hợp đồng hạng mục."); }
                case 27 -> contribute(player, project, "linh_thach", 1);
                case 28 -> contribute(player, project, "huyen_thiet", 1);
                case 29 -> contribute(player, project, "linh_thach_tho", 1);
                case 49 -> request(player, project);
                default -> { }
            }
        }, () -> plugin.getSLF4JLogger().atInfo().addKeyValue("player", holder.owner).log("project_gui_player_retired"));
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }
    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(Component.text(name));
        meta.lore(lore.stream().map(Component::text).toList()); item.setItemMeta(meta); return item;
    }
}
