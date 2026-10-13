package vn.cuuvuc.validation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Plugin can thiệp độc lập; không import core, không gọi API journal hoặc ghi DB. */
public final class InterferenceProbe extends JavaPlugin implements Listener {
    private enum Boundary { NONE, BEFORE_AUTHORIZATION, AFTER_MONITOR }
    private static final UUID BUILDER = UUID.fromString("3bb5ddbc-5b75-377f-8647-6f25c51f252a");
    private final AtomicReference<Boundary> boundary = new AtomicReference<>(Boundary.NONE);
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override public void onEnable() {
        if (!getServer().getWorldContainer().getAbsolutePath().contains("run-b01-if"))
            throw new IllegalStateException("InterferenceProbe chỉ được chạy trong run-b01-if*");
        try { Files.createDirectories(getDataFolder().toPath()); }
        catch (IOException failure) { throw new IllegalStateException("Không tạo được thư mục interference", failure); }
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> poll(), 1, 100, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        Path command = getDataFolder().toPath().resolve("command.txt");
        if (!Files.exists(command) || !busy.compareAndSet(false, true)) return;
        try {
            String[] fields = Files.readString(command, StandardCharsets.UTF_8).strip().split(" ");
            if (fields.length != 2 || !fields[0].matches("[a-z0-9-]+")) throw new IllegalArgumentException("Lệnh interference phải là id action");
            Files.move(command, command.resolveSibling(fields[0] + ".claimed"), StandardCopyOption.ATOMIC_MOVE);
            execute(fields[0], fields[1]);
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            busy.set(false);
            getSLF4JLogger().atError().setCause(failure).log("INTERFERENCE_PROBE_FAILED");
        }
    }

    private void execute(String id, String action) throws IOException {
        switch (action) {
            case "ARM_BEFORE" -> { boundary.set(Boundary.BEFORE_AUTHORIZATION); done(id, "ARMED_BEFORE_AUTHORIZATION"); }
            case "ARM_AFTER" -> { boundary.set(Boundary.AFTER_MONITOR); done(id, "ARMED_AFTER_CORE_MONITOR"); }
            case "ARM_NONE" -> { boundary.set(Boundary.NONE); done(id, "NO_EVENT_INTERFERENCE"); }
            case "WATCH_PRECOMMIT" -> Bukkit.getAsyncScheduler().runNow(this, task -> watchPrecommit(id));
            case "BASELINE_PLACE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> baseline(id, Material.AIR));
            case "BASELINE_BREAK" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> baseline(id, Material.COBBLESTONE));
            case "PLACE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> place(id));
            case "BREAK" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> breakBlock(id));
            case "MUTATE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                block().setType(Material.STONE, false);
                checkpoint("EXTERNAL_MUTATION_DURING_APPLIED_TRANSACTION", block());
                finish(id, "MUTATED_TO_STONE");
            });
            case "OBSERVE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> finish(id, "world=" + block().getType()));
            default -> throw new IllegalArgumentException("Action interference không hợp lệ: " + action);
        }
    }

    /** Chỉ đọc pg_stat_activity của DB thử để đổi world trong cửa sổ transaction, không ghi DB. */
    private void watchPrecommit(String id) {
        try {
            String database = Files.readString(getDataFolder().toPath().resolve("database.txt"), StandardCharsets.UTF_8).strip();
            if (!database.matches("cv_b01_if[a-z0-9_]+")) throw new IllegalArgumentException("Watcher không được dùng DB ngoài B01 interference");
            Class.forName("org.postgresql.Driver");
            try (Connection connection = openObserver(database); Statement query = connection.createStatement()) {
                query.setQueryTimeout(3);
                done(id, "PRECOMMIT_WATCHER_CONNECTED");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (System.nanoTime() < deadline) {
                    try (ResultSet rows = query.executeQuery("SELECT pid,query FROM pg_stat_activity WHERE datname=current_database() AND wait_event='PgSleep' AND query LIKE 'UPDATE operations SET result=%'")) {
                        if (rows.next()) {
                            String marker = "backend_pid=" + rows.getInt(1) + " query=" + rows.getString(2) + " observedUtc=" + Instant.now();
                            Files.writeString(getDataFolder().toPath().resolve("precommit-observed.txt"), marker, StandardCharsets.UTF_8);
                            Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                                mutate("EXTERNAL_MUTATION_DURING_APPLIED_TRANSACTION", block());
                                finish("precommit-mutation", marker + " world=" + block().getType());
                            });
                            return;
                        }
                    }
                    TimeUnit.MILLISECONDS.sleep(25);
                }
                throw new IllegalStateException("Không quan sát được PgSleep của APPLIED trong 20 giây");
            }
        } catch (SQLException | IOException | ClassNotFoundException | IllegalArgumentException | IllegalStateException failure) {
            getSLF4JLogger().atError().setCause(failure).log("INTERFERENCE_PRECOMMIT_WATCH_FAILED");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Watcher precommit bị ngắt", failure);
        }
    }

    private Connection openObserver(String database) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:55439/" + database;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try { return DriverManager.getConnection(url, "cuuvuc_test", "local-trust-test"); }
            catch (SQLException failure) {
                getSLF4JLogger().atWarn().addKeyValue("url", url).addKeyValue("attempt", attempt).addKeyValue("sql_state", failure.getSQLState()).setCause(failure).log("INTERFERENCE_OBSERVER_CONNECTION_FAILED");
                if (attempt == 3) throw failure;
            }
        }
        throw new IllegalStateException("Vòng kết nối observer kết thúc ngoài dự kiến");
    }

    private Block block() { return Objects.requireNonNull(Bukkit.getWorld("cv_sky")).getBlockAt(67, 100, 5); }

    private void baseline(String id, Material material) {
        Block block = block();
        World world = block.getWorld();
        world.setAutoSave(false); block.setType(material, false); world.save(true);
        finish(id, "BASELINE_FLUSHED world=" + material);
    }

    /** Adapter tối thiểu cho fixture event, không đăng nhập hoặc mô phỏng inventory/network của client. */
    private Player fixtureActor() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
            return switch (method.getName()) {
                case "getUniqueId" -> BUILDER;
                case "sendPlainMessage", "sendMessage" -> null;
                case "getName" -> "TyninWorld";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                case "toString" -> "B01_INTERFERENCE_FIXTURE_ACTOR";
                default -> throw new UnsupportedOperationException("Fixture không cung cấp Player method: " + method.getName());
            };
        });
    }

    private void place(String id) {
        Block block = block(); BlockState previous = block.getState();
        if (previous.getType() != Material.AIR) throw new IllegalStateException("Fixture PLACE yêu cầu AIR, actual=" + previous.getType());
        block.setType(Material.COBBLESTONE, false);
        BlockPlaceEvent event = new BlockPlaceEvent(block, previous, block.getRelative(0, -1, 0),
                new ItemStack(Material.COBBLESTONE), fixtureActor(), true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) block.setType(previous.getType(), false);
        checkpoint("PLACE_DISPATCH_RETURNED_CANCELLED_" + event.isCancelled(), block);
        finish(id, "event=PLACE cancelled=" + event.isCancelled() + " world=" + block.getType());
    }

    private void breakBlock(String id) {
        Block block = block();
        if (block.getType() != Material.COBBLESTONE) throw new IllegalStateException("Fixture BREAK yêu cầu COBBLESTONE, actual=" + block.getType());
        BlockBreakEvent event = new BlockBreakEvent(block, fixtureActor());
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            block.setType(Material.AIR, false);
            if (boundary.get() == Boundary.AFTER_MONITOR) mutate("AFTER_BREAK_CALLBACK_RETURN", block);
        }
        checkpoint("BREAK_DISPATCH_RETURNED_CANCELLED_" + event.isCancelled(), block);
        finish(id, "event=BREAK cancelled=" + event.isCancelled() + " world=" + block.getType());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforePlace(BlockPlaceEvent event) {
        if (target(event.getBlock()) && boundary.get() == Boundary.BEFORE_AUTHORIZATION) mutate("BEFORE_PLACE_AUTHORIZATION", event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeBreak(BlockBreakEvent event) {
        if (target(event.getBlock()) && boundary.get() == Boundary.BEFORE_AUTHORIZATION) mutate("BEFORE_BREAK_AUTHORIZATION", event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterPlace(BlockPlaceEvent event) {
        if (target(event.getBlock()) && boundary.get() == Boundary.AFTER_MONITOR) mutate("AFTER_PLACE_CORE_MONITOR", event.getBlock());
    }

    private static boolean target(Block block) {
        return block.getWorld().getName().equals("cv_sky") && block.getX() == 67 && block.getY() == 100 && block.getZ() == 5;
    }

    private void mutate(String phase, Block block) { block.setType(Material.STONE, false); checkpoint(phase, block); }

    private void checkpoint(String phase, Block block) {
        String marker = "utc=" + Instant.now() + " pid=" + ProcessHandle.current().pid() + " phase=" + phase
                + " thread=" + Thread.currentThread().getName() + " position=67:100:5 world=" + block.getType() + "\n";
        try (FileChannel channel = FileChannel.open(getDataFolder().toPath().resolve("markers.txt"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(marker);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException failure) { throw new IllegalStateException("Không ghi được marker plugin interference", failure); }
    }

    private void done(String id, String result) throws IOException {
        Files.writeString(getDataFolder().toPath().resolve(id + ".done"), result, StandardCharsets.UTF_8);
        busy.set(false);
    }

    private void finish(String id, String result) {
        try { done(id, result); }
        catch (IOException failure) { throw new IllegalStateException("Không ghi được kết quả interference: " + id, failure); }
    }
}
