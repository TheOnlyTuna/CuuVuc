package vn.cuuvuc.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static vn.cuuvuc.core.PlayableMenu.icon;

/** Connector GUI và sự kiện động phủ; chỉ snapshot bất biến đi qua scheduler DB/entity. */
public final class CultivationMenu implements Listener {
    private record Glyph(Material material, String name) { }
    private static final List<Glyph> GLYPHS = List.of(new Glyph(Material.REDSTONE, "Hỏa"), new Glyph(Material.LAPIS_LAZULI, "Thủy"),
            new Glyph(Material.OAK_SAPLING, "Mộc"), new Glyph(Material.IRON_INGOT, "Kim"), new Glyph(Material.CLAY_BALL, "Thổ"));
    private static final class Holder implements InventoryHolder {
        private final UUID owner;
        private final UUID request;
        private final Cultivation.Snapshot snapshot;
        private final Inventory inventory;
        private final AtomicBoolean used = new AtomicBoolean();
        private Holder(UUID owner, Cultivation.Snapshot snapshot) {
            this.owner = owner; this.snapshot = snapshot; this.request = UUID.randomUUID();
            inventory = Bukkit.createInventory(this, 27, Component.text("Cửu Vực • Con Đường Tu Tiên"));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final PhysicalItems items;
    private final PlayableWorld world;
    private final Cultivation.Rules rules;
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();

    public CultivationMenu(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, PhysicalItems items, PlayableWorld world) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.items = items; this.world = world;
        this.rules = database.cultivationRules();
    }

    public void request(Player player) {
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return; }
        UUID actor = player.getUniqueId();
        tasks.submitSnapshot(player, UUID.randomUUID(), () -> database.cultivationView(actor, "Khai thác → kết tinh → tu luyện tại động phủ → Trận Tâm Ma."),
                snapshot -> show(player, snapshot), snapshot -> snapshot.view().message());
    }

    private void show(Player player, Cultivation.Snapshot snapshot) {
        if (!items.sync(player, snapshot.view())) return;
        Holder holder = new Holder(player.getUniqueId(), snapshot);
        Cultivation.State state = snapshot.view().cultivation();
        holder.inventory.setItem(4, icon(Material.NETHER_STAR, Cultivation.title(state), List.of("Tu vi tích lũy: " + snapshot.view().xp(),
                "Khai thác: +" + state.rank() * rules.bonus() + " XP/mạch", "XP đủ điều kiện không tự nâng cảnh giới",
                state.legacyTitle().map(value -> "Danh hiệu cũ được bảo toàn: " + value).orElse("Hành trình mới, bắt đầu Phàm Nhân"))));
        holder.inventory.setItem(18, icon(Material.OAK_SAPLING, "Đến động phủ của bạn", List.of("Tu luyện/thử thách chỉ tại động phủ sở hữu")));
        holder.inventory.setItem(20, icon(Material.IRON_PICKAXE, "Khai thác Prison", List.of("Lấy cuốc gỗ trong rương động phủ", "Bấm trái với cuốc vào mạch quặng; hồi 3 giây", "Lấy thô, Huyền Thiết và XP")));
        holder.inventory.setItem(22, icon(Material.CRAFTING_TABLE, "Kết tinh chuẩn bị", List.of("5 thô → 1 Linh Thạch", "Tiêu hao trong kho, không dùng Linh Tệ")));
        Optional<Cultivation.Trial> active = snapshot.trial().filter(value -> value.status().equals("ACTIVE"));
        if (active.isPresent()) trialMenu(player, holder, active.orElseThrow());
        else {
            sessions.remove(player.getUniqueId());
            if (state.rank() < 9) {
                Cultivation.Gate gate = Cultivation.next(rules, state);
                holder.inventory.setItem(10, icon(Material.BOOK, "Luyện Khí tầng " + gate.rank(), requirements(snapshot.view(), gate.xp(), gate.cost())));
                holder.inventory.setItem(13, icon(Material.LIME_DYE, "Tu luyện lên tầng " + gate.rank(), List.of("Tự xác nhận; đủ XP và định mức mới thành công", "Không trừ XP; tiêu hao nguyên liệu một lần")));
            } else if (state.rank() == 9) {
                holder.inventory.setItem(10, icon(Material.BOOK, "Điều kiện Trúc Cơ", requirements(snapshot.view(), rules.trialXp(), rules.trialCost())));
                holder.inventory.setItem(13, icon(Material.ENDER_EYE, "Tham gia Trận Tâm Ma", List.of("Nhớ 5 phù ấn trong " + rules.previewSeconds() + " giây", "Tái hiện đúng thứ tự trước " + rules.durationSeconds() + " giây",
                        "Sai/hết giờ/thoát/rời đảo/restart: thua, không hoàn phí", "Hồi phục " + rules.cooldownSeconds() + " giây sau kết thúc")));
            } else {
                Domain.Recipe recipe = rules.advanced();
                holder.inventory.setItem(24, icon(Material.AMETHYST_BLOCK, "Kết tinh Trúc Cơ — đã mở", List.of(recipe.inputCount() + " thô → " + recipe.outputCount() + " Linh Thạch", "Lợi ích dùng ngay, không đổi thành tiền tự động")));
            }
            snapshot.trial().ifPresent(value -> holder.inventory.setItem(16, icon(Material.PAPER, "Lượt thử: " + value.status(),
                    List.of("Phù ấn đã qua: " + value.step() + "/5", value.reason().orElse(""), "Có thể thử lại từ: " + value.retryAt().map(Object::toString).orElse("—")))));
        }
        holder.inventory.setItem(26, icon(Material.CLOCK, "Làm mới tiến trình", List.of("Đọc lại kho, trạng thái, cooldown từ DB")));
        player.openInventory(holder.inventory);
    }

    private static List<String> requirements(Domain.PlayerView view, long xp, Map<String, Long> cost) {
        java.util.ArrayList<String> lore = new java.util.ArrayList<>();
        lore.add("Tu vi: " + view.xp() + "/" + xp + (view.xp() >= xp ? " — đủ" : " — chưa đủ"));
        for (Map.Entry<String, Long> entry : cost.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            lore.add(entry.getKey() + ": " + view.stock().getOrDefault(entry.getKey(), 0L) + "/" + entry.getValue());
        lore.addAll(Cultivation.missing(view.stock(), cost)); return List.copyOf(lore);
    }

    /** Kết quả khóa ý định mới; người chơi phải đọc/làm mới trước lần tiêu hao hoặc phù ấn tiếp theo. */
    private void showResult(Player player, Cultivation.Snapshot snapshot) {
        show(player, snapshot);
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder)) return;
        holder.used.set(true);
        for (int slot : List.of(13, 22, 24)) {
            if (holder.inventory.getItem(slot) != null) holder.inventory.setItem(slot, icon(Material.GRAY_DYE, "Đã xử lý ý định này",
                    List.of("Bấm Tiếp tục / Làm mới trước thao tác mới", "Bấm lặp không tiêu hao hay tiến thêm tầng")));
        }
        if (snapshot.trial().filter(value -> value.status().equals("ACTIVE")).isPresent())
            for (int slot = 10; slot <= 14; slot++) holder.inventory.setItem(slot, icon(Material.GRAY_DYE, "Đã trả lời phù ấn",
                    List.of("Bấm Tiếp tục để mở phù ấn kế tiếp")));
        holder.inventory.setItem(26, icon(Material.CLOCK, "Tiếp tục / Làm mới", List.of("Đọc lại DB trước khi xác nhận ý định mới")));
    }

    private static boolean confirm(Player player, Holder holder) {
        if (holder.used.compareAndSet(false, true)) return true;
        player.sendPlainMessage("Ý định này đã được gửi; bấm Tiếp tục / Làm mới và kiểm tra kết quả trước thao tác mới.");
        return false;
    }

    /** Giữ nguyên chuỗi hiển thị để ghi nhớ; timer sau preview mới mở ý định trả lời. */
    private void showTrialStarted(Player player, Cultivation.Snapshot snapshot) {
        show(player, snapshot);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) holder.used.set(true);
    }

    private void trialMenu(Player player, Holder holder, Cultivation.Trial trial) {
        sessions.put(player.getUniqueId(), trial.id());
        long seconds = Math.max(0, Duration.between(holder.snapshot.now(), trial.deadline()).getSeconds());
        holder.inventory.setItem(8, icon(Material.CLOCK, "Trận Tâm Ma: " + trial.step() + "/5", List.of("Còn khoảng " + seconds + " giây", "DB kiểm thời hạn ở từng lựa chọn")));
        if (holder.snapshot.now().isBefore(trial.previewUntil())) {
            for (int index = 0; index < trial.sequence().size(); index++) {
                Glyph glyph = GLYPHS.get(trial.sequence().get(index));
                holder.inventory.setItem(index, icon(glyph.material(), "Ghi nhớ " + (index + 1) + ": " + glyph.name(), List.of("Đây là thứ tự; chưa được chọn")));
            }
            long ticks = Math.max(1, Duration.between(holder.snapshot.now(), trial.previewUntil()).toMillis() / 50 + 2);
            player.getScheduler().runDelayed(plugin, task -> {
                if (player.getOpenInventory().getTopInventory().getHolder() == holder) request(player);
            }, () -> plugin.getSLF4JLogger().info("cultivation_preview_player_retired"), ticks);
        } else {
            for (int index = 0; index < GLYPHS.size(); index++) {
                Glyph glyph = GLYPHS.get(index);
                holder.inventory.setItem(10 + index, icon(glyph.material(), "Chọn " + glyph.name(), List.of("Phù ấn thứ " + (trial.step() + 1) + "/5", "Chọn sai kết thúc lượt thử")));
            }
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == holder) request(player);
        }, () -> plugin.getSLF4JLogger().info("cultivation_deadline_player_retired"), Math.max(1, seconds * 20 + 2));
    }

    private boolean ownHome(Player player) { return ownHome(player.getUniqueId(), player.getLocation()); }
    private boolean ownHome(UUID player, Location location) {
        return location.getWorld().getName().equals(PlayableWorld.SKY) && world.ownerIsland(player)
                .filter(Domain.Island::ready).filter(island -> Domain.islandAt(List.of(island), location.getBlockX(), location.getBlockZ()).isPresent()).isPresent();
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId()) || !event.isLeftClick()) return;
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return; }
        int slot = event.getRawSlot();
        if (slot == 18) { world.enterHomeFromMenu(player); return; }
        if (slot == 20) { world.enterPrisonFromMenu(player); return; }
        if (slot == 26) { request(player); return; }
        UUID actor = player.getUniqueId(); String name = player.getName();
        if (slot == 22 || slot == 24) {
            if (!confirm(player, holder)) return;
            String recipe = slot == 22 ? "ket_tinh" : rules.advanced().id();
            tasks.submitSnapshot(player, holder.request, () -> database.cultivationView(actor, database.craft(holder.request, actor, name, recipe, 1)),
                    snapshot -> showResult(player, snapshot), snapshot -> snapshot.view().message());
            return;
        }
        if (!ownHome(player) || player.getGameMode() != GameMode.SURVIVAL) {
            player.sendPlainMessage("Tu luyện và Trận Tâm Ma cần Survival tại động phủ của chính bạn."); return;
        }
        Cultivation.State state = holder.snapshot.view().cultivation();
        Optional<Cultivation.Trial> active = holder.snapshot.trial().filter(value -> value.status().equals("ACTIVE"));
        if (active.isPresent() && slot >= 10 && slot <= 14) {
            if (!confirm(player, holder)) return;
            Cultivation.Trial trial = active.orElseThrow(); int choice = slot - 10;
            UUID intent = UUID.nameUUIDFromBytes(("cuuvuc:cultivation:" + trial.id() + ":" + trial.step()).getBytes(StandardCharsets.UTF_8));
            tasks.submitSnapshot(player, intent, () -> database.cultivationView(actor, database.answerCultivationTrial(intent, actor, trial.id(), trial.step(), choice)),
                    snapshot -> showResult(player, snapshot), snapshot -> snapshot.view().message());
        } else if (active.isEmpty() && slot == 13 && state.rank() < 10) {
            if (!confirm(player, holder)) return;
            PlayerTasks.DatabaseAction action = state.rank() < 9
                    ? () -> database.trainCultivation(holder.request, actor, state.rank(), state.revision())
                    : () -> database.startCultivationTrial(holder.request, actor, state.revision());
            PlayerTasks.SnapshotReply<Cultivation.Snapshot> reply = state.rank() < 9
                    ? snapshot -> showResult(player, snapshot) : snapshot -> showTrialStarted(player, snapshot);
            tasks.submitSnapshot(player, holder.request, () -> database.cultivationView(actor, action.execute()),
                    reply, snapshot -> snapshot.view().message());
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) { if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { interrupt(event.getPlayer().getUniqueId(), "Người chơi đăng xuất"); }
    @EventHandler(ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        if (ownHome(actor, event.getFrom()) && !ownHome(actor, event.getTo())) interrupt(actor, "Người chơi rời động phủ");
    }
    @EventHandler(ignoreCancelled = true) public void move(PlayerMoveEvent event) {
        UUID actor = event.getPlayer().getUniqueId();
        if (sessions.containsKey(actor) && ownHome(actor, event.getFrom()) && !ownHome(actor, event.getTo()))
            interrupt(actor, "Người chơi rời động phủ");
    }
    @EventHandler(ignoreCancelled = true) public void mode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SURVIVAL) interrupt(event.getPlayer().getUniqueId(), "Rời chế độ Survival");
    }
    private void interrupt(UUID actor, String reason) {
        if (sessions.remove(actor) == null) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { database.interruptCultivation(actor, reason); }
            catch (SQLException failure) { plugin.getSLF4JLogger().atError().addKeyValue("player", actor).addKeyValue("reason", reason)
                    .setCause(failure).log("cultivation_interrupt_failed_restart_will_reconcile"); }
        });
    }
}
