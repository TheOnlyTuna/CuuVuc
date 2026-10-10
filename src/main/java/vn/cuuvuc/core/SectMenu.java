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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** GUI chỉ giữ ý định; snapshot không cấp quyền, mỗi xác nhận kiểm tra lại PostgreSQL. */
public final class SectMenu implements Listener {
    private sealed interface Action permits Create, Invite, Accept, Role, Kick, Leave, Cancel { }
    private record Create(String name) implements Action { }
    private record Invite(UUID sect, UUID target) implements Action { }
    private record Accept(UUID invitation) implements Action { }
    private record Role(UUID sect, UUID target, Sects.Role role) implements Action { }
    private record Kick(UUID sect, UUID target) implements Action { }
    private record Leave(UUID sect) implements Action { }
    private record Cancel(UUID invitation) implements Action { }
    private record Intent(UUID request, UUID actor, Action action) { }
    private record Reply(Sects.View view, String message) { }
    private static final class Board implements InventoryHolder {
        private final UUID owner;
        private final Sects.View view;
        private final int page;
        private final Inventory inventory;
        private Board(UUID owner, Sects.View view, int page) {
            this.owner = owner; this.view = view; this.page = page;
            inventory = Bukkit.createInventory(this, 54, Component.text("Cửu Vực • Tông môn"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class Confirmation implements InventoryHolder {
        private final Intent intent;
        private final Inventory inventory;
        private Confirmation(Intent intent) {
            this.intent = intent;
            inventory = Bukkit.createInventory(this, 27, Component.text("Cửu Vực • Xác nhận tông môn"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;

    public SectMenu(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks) {
        this.plugin = plugin; this.database = database; this.tasks = tasks;
    }

    public void request(Player player, int page) {
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.sectView(actor),
                view -> open(player, view, page), view -> "Tông môn đã tải từ DB.");
    }

    public void command(Player player, String[] arguments) {
        if (arguments.length == 1) { request(player, 0); return; }
        UUID actor = player.getUniqueId();
        switch (arguments[1]) {
            case "create" -> {
                if (arguments.length < 3) throw new IllegalArgumentException("Dùng /cv sect create <tên tông môn>.");
                confirm(player, new Create(Sects.name(String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length))).display()));
            }
            case "accept", "cancel" -> {
                if (arguments.length != 3) throw new IllegalArgumentException("Dùng /cv sect accept/cancel <UUID-lời-mời>.");
                UUID invitation = UUID.fromString(arguments[2]);
                confirm(player, arguments[1].equals("accept") ? new Accept(invitation) : new Cancel(invitation));
            }
            case "invite", "role", "kick", "leave" -> {
                String operation = arguments[1];
                int expected = operation.equals("leave") ? 2 : operation.equals("role") ? 4 : 3;
                if (arguments.length != expected) throw new IllegalArgumentException("Dùng /cv sect invite/kick <UUID> | role <UUID> <ELDER/MEMBER> | leave.");
                UUID target = operation.equals("leave") ? actor : UUID.fromString(arguments[2]);
                Sects.Role role = operation.equals("role") ? Sects.Role.valueOf(arguments[3].toUpperCase(java.util.Locale.ROOT)) : Sects.Role.MEMBER;
                tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.sectView(actor), view -> {
                    if (view.organization().isEmpty()) { player.sendPlainMessage("Bạn chưa có tông môn."); return; }
                    UUID sect = view.organization().orElseThrow().id();
                    Action action = switch (operation) {
                        case "invite" -> new Invite(sect, target);
                        case "role" -> new Role(sect, target, role);
                        case "kick" -> new Kick(sect, target);
                        case "leave" -> new Leave(sect);
                        default -> throw new IllegalStateException("Thao tác tông môn không hợp lệ: " + operation);
                    };
                    confirm(player, action);
                }, view -> "Kiểm tra đích và xác nhận thao tác tông môn.");
            }
            default -> throw new IllegalArgumentException("/cv sect | create <tên> | invite <UUID> | accept/cancel <lời-mời> | role <UUID> <ELDER/MEMBER> | kick <UUID> | leave");
        }
    }

    private void open(Player player, Sects.View view, int requestedPage) {
        int page = Math.max(0, Math.min(requestedPage, Math.max(0, (view.members().size() - 1) / 18)));
        Board board = new Board(player.getUniqueId(), view, page);
        String name = view.organization().map(Sects.Organization::name).orElse("Chưa có tông môn");
        board.inventory.setItem(4, icon(Material.BEACON, name, view.organization().map(sect -> List.of("ID: " + sect.id(), "Quyền: " + sect.role(), "Revision: " + sect.revision(), "Mời: /cv sect invite <UUID>")).orElse(List.of("/cv sect create <tên>", "Lời mời nhận ở hàng bên dưới"))));
        board.inventory.setItem(7, icon(Material.WRITABLE_BOOK, "Lịch sử tổ chức • 10 sự kiện gần nhất", view.history().stream().flatMap(event -> Stream.of(
                DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneOffset.UTC).format(event.at()) + " UTC • " + event.type(),
                event.actorName() + " → " + event.targetName() + (event.type().equals("ROLE") ? " • " + event.detail() : ""))).toList()));
        for (int index = 0; index < 18 && page * 18 + index < view.members().size(); index++) {
            Sects.Member member = view.members().get(page * 18 + index);
            board.inventory.setItem(9 + index, icon(Material.PLAYER_HEAD, member.name() + " • " + member.role(), List.of(member.player().toString(), "Chưởng môn: trái đổi MEMBER/ELDER", "Chưởng môn: phải loại thành viên", "Mọi thao tác cần xác nhận và quyền DB")));
        }
        for (int index = 0; index < view.incoming().size(); index++) {
            Sects.Invitation invitation = view.incoming().get(index);
            board.inventory.setItem(27 + index, icon(Material.PAPER, "Nhận lời mời • " + invitation.name(), List.of(invitation.id().toString(), "Hết hạn: " + invitation.expiresAt(), "Bấm để mở xác nhận")));
        }
        for (int index = 0; index < view.outgoing().size(); index++) {
            Sects.Invitation invitation = view.outgoing().get(index);
            board.inventory.setItem(36 + index, icon(Material.RED_DYE, "Hủy lời mời đã gửi", List.of(invitation.id().toString(), "Đích: " + invitation.recipient(), "Hết hạn: " + invitation.expiresAt())));
        }
        if (view.organization().isPresent()) board.inventory.setItem(45, icon(Material.OAK_DOOR, "Rời tông môn", List.of("Chưởng môn chưa thể rời trong prototype")));
        board.inventory.setItem(48, icon(Material.ARROW, "Trang trước", List.of("Trang thành viên: " + (page + 1))));
        board.inventory.setItem(49, icon(Material.CLOCK, "Làm mới quyền và lịch sử", List.of("Lời mời: tối đa 9 gần nhất mỗi nhóm")));
        board.inventory.setItem(50, icon(Material.ARROW, "Trang sau", List.of("Thành viên: " + view.members().size())));
        player.openInventory(board.inventory);
    }

    private void confirm(Player player, Action action) {
        Confirmation holder = new Confirmation(new Intent(UUID.randomUUID(), player.getUniqueId(), action));
        holder.inventory.setItem(11, icon(Material.PAPER, "Kiểm tra thao tác", List.of(describe(action), "Quyền sẽ được kiểm tra lại tại DB", "Đóng cửa sổ để bỏ ý định")));
        holder.inventory.setItem(15, icon(Material.LIME_DYE, "Xác nhận", List.of("Request: " + holder.intent.request(), "Cùng ý định chỉ thực hiện một lần")));
        player.openInventory(holder.inventory);
    }

    private static String describe(Action action) {
        return switch (action) {
            case Create create -> "Thành lập: " + create.name();
            case Invite invite -> "Mời " + invite.target() + " vào " + invite.sect();
            case Accept accept -> "Nhận lời mời " + accept.invitation();
            case Role role -> "Đổi quyền " + role.target() + " → " + role.role();
            case Kick kick -> "Loại thành viên " + kick.target();
            case Leave leave -> "Rời tông môn " + leave.sect();
            case Cancel cancel -> "Hủy lời mời " + cancel.invitation();
        };
    }

    private String execute(Intent intent) throws SQLException {
        UUID request = intent.request(); UUID actor = intent.actor();
        return switch (intent.action()) {
            case Create create -> database.createSect(request, actor, create.name());
            case Invite invite -> database.inviteSect(request, actor, invite.sect(), invite.target());
            case Accept accept -> database.acceptSect(request, actor, accept.invitation());
            case Role role -> database.changeSectRole(request, actor, role.sect(), role.target(), role.role());
            case Kick kick -> database.kickSectMember(request, actor, kick.sect(), kick.target());
            case Leave leave -> database.leaveSect(request, actor, leave.sect());
            case Cancel cancel -> database.cancelSectInvitation(request, actor, cancel.invitation());
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
                if (!confirmation.intent.actor().equals(player.getUniqueId()) || slot != 15 || !left) return;
                Intent intent = confirmation.intent;
                tasks.submitSnapshot(player, intent.request(), () -> {
                    String message = execute(intent);
                    return new Reply(database.sectView(intent.actor()), message);
                }, reply -> {
                    if (player.getOpenInventory().getTopInventory().getHolder() == holder) open(player, reply.view(), 0);
                }, Reply::message);
            } else if (holder instanceof Board board && board.owner.equals(player.getUniqueId())) {
                if (slot == 49) { request(player, board.page); return; }
                if (slot == 48) { request(player, Math.max(0, board.page - 1)); return; }
                if (slot == 50) { request(player, board.page + 1); return; }
                if (slot >= 27 && slot < 27 + board.view.incoming().size()) { confirm(player, new Accept(board.view.incoming().get(slot - 27).id())); return; }
                if (slot >= 36 && slot < 36 + board.view.outgoing().size()) { confirm(player, new Cancel(board.view.outgoing().get(slot - 36).id())); return; }
                if (board.view.organization().isEmpty()) return;
                Sects.Organization sect = board.view.organization().orElseThrow();
                if (slot == 45) { confirm(player, new Leave(sect.id())); return; }
                int index = board.page * 18 + slot - 9;
                if (slot < 9 || slot > 26 || index >= board.view.members().size() || sect.role() != Sects.Role.LEADER) return;
                Sects.Member target = board.view.members().get(index);
                if (target.role() == Sects.Role.LEADER) return;
                if (right) confirm(player, new Kick(sect.id(), target.player()));
                else confirm(player, new Role(sect.id(), target.player(), target.role() == Sects.Role.MEMBER ? Sects.Role.ELDER : Sects.Role.MEMBER));
            }
        }, () -> plugin.getSLF4JLogger().atInfo().addKeyValue("player", player.getUniqueId()).log("sect_player_retired_before_gui_action"));
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
