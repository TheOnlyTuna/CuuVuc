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

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ý định GUI bất biến; quyền chi không đến từ snapshot và request không đổi khi bấm lặp. */
public final class TreasuryMenu implements Listener {
    private sealed interface Action permits Contribute, Withdraw, Grant, Revoke, Hire { }
    private record Contribute(long amount) implements Action { }
    private record Withdraw(long amount) implements Action { }
    private record Grant(UUID target) implements Action { }
    private record Revoke(UUID target) implements Action { }
    private record Hire(long reward) implements Action { }
    private record Intent(UUID request, UUID actor, UUID sect, Action action) { }
    private record Reply(Treasury.View view, String message) { }
    private static final class Board implements InventoryHolder {
        private final UUID owner;
        private final Treasury.View view;
        private final int page;
        private final Inventory inventory;
        private Board(UUID owner, Treasury.View view, int page) {
            this.owner = owner; this.view = view; this.page = page;
            inventory = Bukkit.createInventory(this, 54, Component.text("Cửu Vực • Ngân khố Linh Tệ"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class Confirmation implements InventoryHolder {
        private final Intent intent;
        private final AtomicBoolean consumed = new AtomicBoolean(false);
        private final Inventory inventory;
        private Confirmation(Intent intent) {
            this.intent = intent;
            inventory = Bukkit.createInventory(this, 27, Component.text("Cửu Vực • Xác nhận ngân khố"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final ContractWorld contracts;
    private final PlayableWorld world;

    public TreasuryMenu(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, ContractWorld contracts, PlayableWorld world) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.contracts = contracts; this.world = world;
    }

    public void request(Player player, int page) {
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.treasuryView(actor),
                view -> open(player, view, page), view -> "Ngân khố đã tải từ DB.");
    }

    public void command(Player player, String[] arguments) {
        if (arguments.length == 1) { request(player, 0); return; }
        if (arguments.length != 3) throw new IllegalArgumentException("/cv treasury | contribute/withdraw/hire <1..1000> | grant/revoke <UUID>");
        Action action = switch (arguments[1]) {
            case "contribute" -> new Contribute(amount(arguments[2]));
            case "withdraw" -> new Withdraw(amount(arguments[2]));
            case "hire" -> new Hire(amount(arguments[2]));
            case "grant" -> new Grant(UUID.fromString(arguments[2]));
            case "revoke" -> new Revoke(UUID.fromString(arguments[2]));
            default -> throw new IllegalArgumentException("Dùng contribute, withdraw, hire, grant hoặc revoke.");
        };
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.treasuryView(actor),
                view -> confirm(player, view.sect(), action), view -> "Kiểm tra và xác nhận thao tác ngân khố.");
    }

    private static long amount(String value) {
        long amount = Long.parseLong(value);
        if (amount < 1 || amount > 1000) throw new IllegalArgumentException("Số Linh Tệ cần từ 1 đến 1000.");
        return amount;
    }

    private void open(Player player, Treasury.View view, int requestedPage) {
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (view.members().size() - 1) / 18)));
        Board board = new Board(player.getUniqueId(), view, page);
        board.inventory.setItem(4, icon(Material.GOLD_BLOCK, view.name(), List.of("SECT: " + view.sect(), "Ngân khố: " + view.balance() + " Linh Tệ", "Ví bạn: " + view.wallet(), "Quyền chi: " + view.spending(), "Phí 0%; Linh Thạch là nguyên liệu")));
        board.inventory.setItem(7, icon(Material.WRITABLE_BOOK, "Ledger ngân khố • 10 gần nhất", view.history()));
        for (int index = 0; index < 18 && page * 18 + index < view.members().size(); index++) {
            Treasury.Member member = view.members().get(page * 18 + index);
            board.inventory.setItem(9 + index, icon(Material.PLAYER_HEAD, member.name(), List.of("Vai trò: " + member.role(), "Quyền chi riêng: " + member.spending(), "Chưởng môn: trái cấp / phải thu hồi", "Chỉ cấp cho ELDER; hạ chức sẽ thu hồi")));
        }
        board.inventory.setItem(27, icon(Material.GOLD_NUGGET, "Góp 2 Linh Tệ", List.of("Ví bạn → ngân khố")));
        board.inventory.setItem(28, icon(Material.GOLD_INGOT, "Góp 10 Linh Tệ", List.of("Ví bạn → ngân khố")));
        if (view.spending()) {
            board.inventory.setItem(30, icon(Material.CHEST, "Rút 2 Linh Tệ", List.of("Ngân khố → ví người chi")));
            board.inventory.setItem(31, icon(Material.CHEST, "Rút 10 Linh Tệ", List.of("Ngân khố → ví người chi")));
            board.inventory.setItem(33, icon(Material.BRICKS, "Thuê Builder: 2 Linh Tệ", terms()));
            board.inventory.setItem(34, icon(Material.BRICKS, "Thuê Builder: 10 Linh Tệ", terms()));
        }
        board.inventory.setItem(48, icon(Material.ARROW, "Trang trước", List.of()));
        board.inventory.setItem(49, icon(Material.CLOCK, "Làm mới", List.of("/cv treasury contribute/withdraw/hire <số>", "Hợp đồng và nghiệm thu: /cv contracts")));
        board.inventory.setItem(50, icon(Material.ARROW, "Trang sau", List.of()));
        player.openInventory(board.inventory);
    }

    private static List<String> terms() {
        return List.of("Ký quỹ từ SECT; hoàn tiền về SECT", "Sàn 3×3 cobblestone, y=100 tại động phủ của bạn", "Dọn trống sàn trước; thời hạn 1 giờ; phí 0%", "Hủy khi chưa nhận; bỏ việc/hết hạn: hoàn ký quỹ", "Vật liệu tự thỏa thuận, chưa có kho dự án", "Duyệt hoặc hủy cần quyền chi hiện tại trong DB");
    }

    private void confirm(Player player, UUID sect, Action action) {
        Confirmation holder = new Confirmation(new Intent(UUID.randomUUID(), player.getUniqueId(), sect, action));
        List<String> description = action instanceof Hire ? Stream.concat(Stream.of(describe(action)), terms().stream()).toList() : List.of(describe(action), "Kiểm tra quyền hiện tại trong DB khi xác nhận");
        holder.inventory.setItem(11, icon(Material.PAPER, "Kiểm tra thao tác", description));
        holder.inventory.setItem(15, icon(Material.LIME_DYE, "Xác nhận", List.of("Cùng ý định chỉ thực hiện một lần", "Đóng cửa sổ để bỏ ý định")));
        player.openInventory(holder.inventory);
    }

    private static String describe(Action action) {
        return switch (action) {
            case Contribute value -> "Góp " + value.amount() + " Linh Tệ";
            case Withdraw value -> "Rút " + value.amount() + " Linh Tệ về ví bạn";
            case Grant value -> "Cấp quyền chi cho " + value.target();
            case Revoke value -> "Thu hồi quyền chi của " + value.target();
            case Hire value -> "Ký quỹ " + value.reward() + " Linh Tệ thuê Builder";
        };
    }

    private String execute(Intent intent) throws SQLException {
        return switch (intent.action()) {
            case Contribute value -> database.contributeTreasury(intent.request(), intent.actor(), intent.sect(), value.amount());
            case Withdraw value -> database.withdrawTreasury(intent.request(), intent.actor(), intent.sect(), value.amount());
            case Grant value -> database.grantTreasurySpend(intent.request(), intent.actor(), intent.sect(), value.target());
            case Revoke value -> database.revokeTreasurySpend(intent.request(), intent.actor(), intent.sect(), value.target());
            case Hire ignored -> throw new IllegalStateException("Tạo hợp đồng phải qua kiểm tra vùng Paper.");
        };
    }

    @EventHandler public void click(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Board) && !(holder instanceof Confirmation)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("cuuvuc.play")) return;
        int slot = event.getRawSlot(); boolean left = event.isLeftClick(); boolean right = event.isRightClick();
        if (!left && !right) return;
        player.getScheduler().run(plugin, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder() != holder || !player.hasPermission("cuuvuc.play")) return;
            if (holder instanceof Confirmation confirmation) {
                Intent intent = confirmation.intent;
                if (!intent.actor().equals(player.getUniqueId()) || !left) return;
                if (slot == 22 && confirmation.consumed.get()) { request(player, 0); return; }
                if (slot != 15 || !confirmation.consumed.compareAndSet(false, true)) return;
                confirmation.inventory.setItem(15, icon(Material.GRAY_DYE, "Ý định đã gửi", List.of("Xem kết quả trong chat; không gửi thêm khi bấm lặp")));
                if (intent.action() instanceof Hire hire) {
                    try {
                        Domain.Island island = world.ownerIsland(intent.actor()).orElseThrow(() -> new IllegalArgumentException("Dùng /cv home để tạo động phủ trước."));
                        player.closeInventory(); contracts.createSect(player, intent.request(), intent.sect(), hire.reward(), island);
                    } catch (IllegalArgumentException failure) { player.sendPlainMessage(failure.getMessage()); }
                    return;
                }
                tasks.submitSnapshot(player, intent.request(), () -> {
                    String message = execute(intent);
                    return new Reply(database.treasuryView(intent.actor()), message);
                }, reply -> {
                    if (player.getOpenInventory().getTopInventory().getHolder() != holder) return;
                    confirmation.inventory.setItem(11, icon(Material.PAPER, "Giao dịch đã commit", List.of("Ngân khố: " + reply.view().balance() + " Linh Tệ", "Ví bạn: " + reply.view().wallet())));
                    confirmation.inventory.setItem(22, icon(Material.CLOCK, "Xem ngân khố", List.of("Tải snapshot mới từ DB")));
                }, Reply::message);
                return;
            }
            Board board = (Board) holder;
            if (!board.owner.equals(player.getUniqueId())) return;
            if (slot == 48 || slot == 49 || slot == 50) { request(player, slot == 48 ? board.page - 1 : slot == 50 ? board.page + 1 : board.page); return; }
            if (slot >= 9 && slot < 27 && board.view.leader()) {
                int index = board.page * 18 + slot - 9;
                if (index < board.view.members().size()) {
                    UUID target = board.view.members().get(index).id();
                    confirm(player, board.view.sect(), left ? new Grant(target) : new Revoke(target));
                }
                return;
            }
            if (!left) return;
            Optional<Action> action = switch (slot) {
                case 27 -> Optional.of(new Contribute(2)); case 28 -> Optional.of(new Contribute(10));
                case 30 -> Optional.of(new Withdraw(2)); case 31 -> Optional.of(new Withdraw(10));
                case 33 -> Optional.of(new Hire(2)); case 34 -> Optional.of(new Hire(10));
                default -> Optional.empty();
            };
            if (action.isPresent() && board.inventory.getItem(slot) != null) confirm(player, board.view.sect(), action.orElseThrow());
        }, () -> plugin.getSLF4JLogger().atInfo().addKeyValue("player", player.getUniqueId()).log("treasury_player_retired_before_gui_action"));
    }

    @EventHandler public void drag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof Board || holder instanceof Confirmation) event.setCancelled(true);
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name)); meta.lore(lore.stream().map(Component::text).toList()); item.setItemMeta(meta);
        return item;
    }
}
