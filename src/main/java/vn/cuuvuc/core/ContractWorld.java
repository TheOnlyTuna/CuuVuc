package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Connector Paper: vùng thi công nhỏ, lease quyền ngắn, kiểm tra và lưu world trước nghiệm thu. */
public final class ContractWorld implements Listener {
    private record Snapshot(Contracts.Board board, long observedAt) { }
    private record Change(UUID request, UUID contract, UUID actor, String event, String position) { }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(new Contracts.Board(-1, Instant.EPOCH, List.of()), 0));
    private final ConcurrentHashMap<UUID, UUID> fences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UUID> creating = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Change> changes = new ArrayBlockingQueue<>(128);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile boolean auditInFlight;

    public ContractWorld(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks) {
        this.plugin = plugin; this.database = database; this.tasks = tasks;
    }

    public void publish(Contracts.Board board) {
        Snapshot next = new Snapshot(board, System.nanoTime());
        snapshot.updateAndGet(old -> board.revision() > old.board().revision()
                || board.revision() == old.board().revision() && !board.databaseTime().isBefore(old.board().databaseTime()) ? next : old);
    }

    public void start() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> refresh(), 1, 1, TimeUnit.SECONDS);
    }

    /** Shutdown mềm lưu hàng đợi chưa commit; request ID bảo đảm replay không ghi trùng. */
    public synchronized void savePendingAudit() throws IOException {
        Properties pending = new Properties();
        for (Change change : changes) pending.setProperty(change.request().toString(), change.contract() + "," + change.actor() + "," + change.event() + "," + change.position());
        Path target = plugin.getDataFolder().toPath().resolve("contract-audit-pending.properties");
        Path temporary = target.resolveSibling("contract-audit-pending.properties.tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            pending.store(writer, "Lịch sử block cần đối chiếu sau shutdown mềm; không phải journal chống crash");
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void recoverAudit() throws IOException, SQLException {
        Path path = plugin.getDataFolder().toPath().resolve("contract-audit-pending.properties");
        if (!Files.exists(path)) return;
        Properties pending = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { pending.load(reader); }
        for (String key : pending.stringPropertyNames()) {
            String[] fields = pending.getProperty(key).split(",", -1);
            if (fields.length != 4) throw new IOException("Journal lịch sử block không hợp lệ: " + path + " request=" + key);
            database.contractBlockChanged(UUID.fromString(key), UUID.fromString(fields[0]), UUID.fromString(fields[1]), fields[2], fields[3]);
        }
        savePendingAudit();
    }

    private String executeAndSync(PlayerTasks.DatabaseAction work, Runnable releaseFence) throws SQLException {
        try {
            String receipt = work.execute(); publish(database.contractBoard()); releaseFence.run(); return receipt;
        } catch (SQLException | IllegalArgumentException | ArithmeticException failure) {
            try { publish(database.contractBoard()); releaseFence.run(); }
            catch (SQLException refreshFailure) {
                failure.addSuppressed(refreshFailure);
                plugin.getSLF4JLogger().atError().setCause(refreshFailure).log("contract_result_unknown_fence_retained_until_restart");
            }
            throw failure;
        }
    }

    private void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            Change change = changes.peek();
            while (change != null) {
                auditInFlight = true;
                database.contractBlockChanged(change.request(), change.contract(), change.actor(), change.event(), change.position());
                changes.remove(); auditInFlight = false; change = changes.peek();
            }
            database.expireContracts(); publish(database.contractBoard());
        } catch (SQLException | IllegalArgumentException failure) {
            plugin.getSLF4JLogger().atError().setCause(failure).log("contract_refresh_failed_rights_expire_after_lease");
        } finally { auditInFlight = false; refreshing.set(false); }
    }

    public Contracts.Board board() { return snapshot.get().board(); }

    public boolean fresh() { return snapshot.get().board().revision() >= 0 && System.nanoTime() - snapshot.get().observedAt() < TimeUnit.SECONDS.toNanos(5); }

    public Optional<Contracts.Job> at(Block block) {
        return atSnapshot(snapshot.get(), block);
    }

    private static Optional<Contracts.Job> atSnapshot(Snapshot current, Block block) {
        if (!block.getWorld().getName().equals(PlayableWorld.SKY)) return Optional.empty();
        return current.board().jobs().stream().filter(Contracts::unresolved)
                .filter(job -> Contracts.contains(job, block.getX(), block.getY(), block.getZ())).findFirst();
    }

    public boolean permission(UUID actor, Block block, boolean islandPermission) {
        if (block.getWorld().getName().equals(PlayableWorld.SKY) && block.getY() >= 100 && block.getY() <= 103
                && block.getZ() >= 7 && block.getZ() <= 9 && creating.keySet().stream().anyMatch(slot -> Math.abs(block.getX() - (slot * 64 + 8)) <= 1)) return false;
        Snapshot current = snapshot.get();
        Optional<Contracts.Job> contract = atSnapshot(current, block);
        if (contract.isEmpty()) return islandPermission;
        Contracts.Job job = contract.get();
        Instant estimatedTime = current.board().databaseTime().plusNanos(System.nanoTime() - current.observedAt());
        return System.nanoTime() - current.observedAt() < TimeUnit.SECONDS.toNanos(5) && !fences.containsKey(job.id()) && block.getY() == 100
                && Contracts.builderCanBuild(job, actor, block.getX(), block.getY(), block.getZ(), estimatedTime) && snapshot.get() == current;
    }

    public boolean placementSupport(UUID actor, Block support, Block target) {
        return !support.getType().isInteractable() && permission(actor, target, false)
                && at(target).isPresent() && target.getY() == 100;
    }

    public void create(Player player, long reward, Domain.Island island) {
        UUID contract = UUID.randomUUID(); UUID owner = player.getUniqueId();
        createChecked(player, contract, island, () -> database.createContract(contract, owner, reward, Duration.ofHours(1)));
    }

    public void createSect(Player player, UUID contract, UUID sect, long reward, Domain.Island island) {
        UUID owner = player.getUniqueId();
        createChecked(player, contract, island, () -> database.createSectContract(contract, owner, sect, reward, Duration.ofHours(1)));
    }

    private void createChecked(Player player, UUID contract, Domain.Island island, PlayerTasks.DatabaseAction work) {
        if (!fresh() || !island.ready() || !island.owner().equals(player.getUniqueId())) { player.sendPlainMessage("Cần động phủ và dữ liệu hợp đồng sẵn sàng."); return; }
        UUID request = Contracts.request(contract, "CREATE");
        if (creating.putIfAbsent(island.slot(), contract) != null) { player.sendPlainMessage("Động phủ đang tạo hợp đồng."); return; }
        World world = java.util.Objects.requireNonNull(Bukkit.getWorld(PlayableWorld.SKY)); int center = island.slot() * 64 + 8;
        Bukkit.getRegionScheduler().execute(plugin, new Location(world, center, 100, 8), () -> {
            for (int x = center - 1; x <= center + 1; x++) for (int z = 7; z <= 9; z++) {
                if (!world.getBlockAt(x, 100, z).getType().isAir()) {
                    creating.remove(island.slot(), contract); message(player, "Dọn trống 9 ô sàn tại tâm động phủ, y=100 trước khi thuê xây."); return;
                }
            }
            player.getScheduler().run(plugin, task -> {
                boolean accepted = tasks.submit(player, request, () -> {
                    return executeAndSync(work, () -> creating.remove(island.slot(), contract));
                });
                if (!accepted) creating.remove(island.slot(), contract);
            }, () -> { creating.remove(island.slot(), contract); plugin.getSLF4JLogger().info("contract_create_player_retired"); });
        });
    }

    public void accept(Player player, UUID contract) {
        UUID actor = player.getUniqueId();
        transact(player, contract, "ACCEPT", () -> database.acceptContract(contract, actor));
    }

    public void cancel(Player player, UUID contract) {
        UUID actor = player.getUniqueId();
        transact(player, contract, "CANCEL", () -> database.cancelContract(contract, actor));
    }

    public void abandon(Player player, UUID contract) {
        UUID actor = player.getUniqueId();
        transact(player, contract, "ABANDON", () -> database.abandonContract(contract, actor));
    }

    private void transact(Player player, UUID contract, String action, PlayerTasks.DatabaseAction work) {
        UUID request = Contracts.request(contract, action);
        if (fences.putIfAbsent(contract, request) != null) { player.sendPlainMessage("Hợp đồng đang xử lý. Làm mới để kiểm tra."); return; }
        boolean accepted = tasks.submit(player, request, () -> executeAndSync(work, () -> fences.remove(contract, request)));
        if (!accepted) fences.remove(contract, request);
    }

    public void submit(Player player, Contracts.Job job) {
        if (job.builder().filter(player.getUniqueId()::equals).isEmpty() || job.status() != Contracts.Status.ACTIVE) {
            player.sendPlainMessage("Bạn chưa nhận hợp đồng này."); return;
        }
        UUID actor = player.getUniqueId();
        checkAndSave(player, job, Contracts.request(job.id(), "SUBMIT"), () -> database.submitContract(job.id(), actor));
    }

    public void complete(Player player, Contracts.Job job) {
        if ((job.fundingSect().isEmpty() && !job.owner().equals(player.getUniqueId())) || job.status() != Contracts.Status.SUBMITTED) {
            player.sendPlainMessage("Chỉ chủ hợp đồng được nghiệm thu công trình đang chờ duyệt."); return;
        }
        UUID actor = player.getUniqueId();
        checkAndSave(player, job, Contracts.request(job.id(), "COMPLETE"), () -> database.completeContract(job.id(), actor));
    }

    /** Công trình mẫu: sàn 3×3 cobblestone tại y=100; toàn bộ vùng nằm trong một chunk. */
    private void checkAndSave(Player player, Contracts.Job job, UUID request, PlayerTasks.DatabaseAction work) {
        if (!fresh() || !changes.isEmpty() || auditInFlight) { player.sendPlainMessage("Chờ đồng bộ lịch sử xây dựng rồi thử lại."); return; }
        if (fences.putIfAbsent(job.id(), request) != null) { player.sendPlainMessage("Hợp đồng đang xử lý."); return; }
        World world = java.util.Objects.requireNonNull(Bukkit.getWorld(PlayableWorld.SKY));
        Bukkit.getRegionScheduler().execute(plugin, new Location(world, job.centerX(), 100, 8), () -> {
            for (int x = job.centerX() - 1; x <= job.centerX() + 1; x++) for (int z = 7; z <= 9; z++) {
                if (world.getBlockAt(x, 100, z).getType() != Material.COBBLESTONE) {
                    fences.remove(job.id(), request); message(player, "Cần đủ 9 block cobblestone trên sàn 3×3 tại y=100."); return;
                }
            }
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                world.save();
                player.getScheduler().run(plugin, task -> {
                    boolean accepted = tasks.submit(player, request, () -> executeAndSync(work, () -> fences.remove(job.id(), request)));
                    if (!accepted) fences.remove(job.id(), request);
                }, () -> { fences.remove(job.id(), request); plugin.getSLF4JLogger().info("contract_checkpoint_player_retired"); });
            });
        });
    }

    private void message(Player player, String text) {
        player.getScheduler().run(plugin, task -> player.sendPlainMessage(text), () -> plugin.getSLF4JLogger().info("contract_player_retired"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void protectPlace(BlockPlaceEvent event) {
        Optional<Contracts.Job> job = at(event.getBlock());
        if (job.isPresent() && (event.getBlock().getY() != 100 || event.getBlock().getType() != Material.COBBLESTONE
                || changes.remainingCapacity() == 0 || !permission(event.getPlayer().getUniqueId(), event.getBlock(), false))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void protectBreak(BlockBreakEvent event) {
        if (at(event.getBlock()).isPresent() && (event.getBlock().getY() != 100 || event.getBlock().getType() != Material.COBBLESTONE
                || changes.remainingCapacity() == 0 || !permission(event.getPlayer().getUniqueId(), event.getBlock(), false))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void placed(BlockPlaceEvent event) {
        record(event.getPlayer().getUniqueId(), event.getBlock(), "PLACE");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void broken(BlockBreakEvent event) {
        record(event.getPlayer().getUniqueId(), event.getBlock(), "BREAK");
    }

    private void record(UUID actor, Block block, String event) {
        at(block).ifPresent(job -> {
            Change change = new Change(UUID.randomUUID(), job.id(), actor, event, block.getX() + ":" + block.getY() + ":" + block.getZ());
            if (!changes.offer(change)) {
                fences.putIfAbsent(job.id(), change.request());
                plugin.getSLF4JLogger().atError().addKeyValue("contract", job.id()).addKeyValue("position", change.position()).log("contract_audit_queue_full_rights_fenced");
            }
        });
    }
}
