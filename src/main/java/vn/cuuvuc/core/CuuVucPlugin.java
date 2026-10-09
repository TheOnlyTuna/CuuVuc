package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class CuuVucPlugin extends JavaPlugin implements Listener {
    private enum State { STARTING, READY, STOPPED }
    private final AtomicReference<State> state = new AtomicReference<>(State.STARTING);
    private GameDatabase database;
    private PlayerTasks tasks;
    private MineNodes mine;
    private Domain.Catalog catalog;
    private Duration shutdownTimeout;

    @Override public void onEnable() {
        saveDefaultConfig();
        long timeoutMillis = getConfig().getLong("shutdown.drain-timeout-ms");
        if (timeoutMillis < 1 || timeoutMillis > 30000) throw new IllegalStateException("Cần cấu hình shutdown.drain-timeout-ms từ 1 đến 30000 cho V0.5.1.");
        shutdownTimeout = Duration.ofMillis(timeoutMillis);
        try { catalog = CatalogFile.read(getResource("catalog.properties")); }
        catch (IOException failure) { throw new IllegalStateException("Không đọc được catalog.", failure); }
        String passwordVariable = Objects.requireNonNull(getConfig().getString("database.password-environment"));
        String password = System.getenv(passwordVariable);
        if (password == null || password.isBlank()) throw new IllegalStateException("Chưa đặt biến môi trường " + passwordVariable);
        database = new GameDatabase(Objects.requireNonNull(getConfig().getString("database.url")),
                Objects.requireNonNull(getConfig().getString("database.user")), password,
                Objects.requireNonNull(getConfig().getString("database.schema")), catalog,
                new Domain.EconomyPolicy(getConfig().getLong("economy.server-daily-mint"), getConfig().getLong("economy.player-daily-mint")));
        tasks = new PlayerTasks(this, getConfig().getInt("mine.max-pending"));
        mine = new MineNodes(this, database, tasks, catalog, this::isReady,
                Objects.requireNonNull(getConfig().getString("mine.world")), getConfig().getStringList("mine.nodes"),
                getConfig().getInt("mine.cooldown-seconds"));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(mine, this);
        Objects.requireNonNull(getCommand("cv")).setExecutor(this);
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                database.initialize();
                if (state.compareAndSet(State.STARTING, State.READY)) getSLF4JLogger().info("cuuvuc_database_ready");
            } catch (SQLException | IOException failure) {
                getSLF4JLogger().atError().setCause(failure).log("cuuvuc_startup_failed");
                if (state.get() != State.STOPPED)
                    Bukkit.getGlobalRegionScheduler().execute(this, () -> getServer().getPluginManager().disablePlugin(this));
            }
        });
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (isReady()) register(event.getPlayer());
        else event.getPlayer().sendPlainMessage("Cửu Vực đang khởi động. Dùng /cv profile sau ít giây.");
    }

    private void showProfile(Player player) {
        UUID id = player.getUniqueId();
        UUID request = UUID.randomUUID();
        tasks.submit(player, request, () -> database.profile(id));
    }

    private void register(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        tasks.submit(player, UUID.randomUUID(), () -> database.register(id, name));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player)) { sender.sendMessage("CuuVucCore V0.5.1 | database-ready=" + isReady()); return true; }
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return true; }
        if (!isReady()) { player.sendPlainMessage("Cơ sở dữ liệu chưa sẵn sàng."); return true; }
        if (arguments.length == 0 || arguments[0].equals("help")) {
            player.sendPlainMessage("/cv start | profile | kho | catalog | craft <recipe> <1..1000> | buy/sell <resource> <1..1000> | pay <UUID> <1..1000> | ledger | receipt <request UUID> | economy (OP) | minebuild (OP)");
            return true;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        UUID request = UUID.randomUUID();
        try {
            switch (arguments[0]) {
                case "start" -> register(player);
                case "profile", "kho" -> showProfile(player);
                case "catalog" -> {
                    for (Domain.Resource resource : catalog.resources().values()) player.sendPlainMessage(resource.id() + " | " + resource.name() + " | mua=" + resource.buy() + " bán=" + resource.sell());
                    for (Domain.Recipe recipe : catalog.recipes().values()) player.sendPlainMessage(recipe.id() + " | " + recipe.inputCount() + " " + recipe.input() + " → " + recipe.outputCount() + " " + recipe.output());
                    player.sendPlainMessage("UUID của bạn: " + id);
                }
                case "ledger" -> tasks.submit(player, request, () -> database.history(id));
                case "receipt" -> {
                    if (arguments.length != 2) throw new IllegalArgumentException("Dùng /cv receipt <request UUID>.");
                    UUID receipt = UUID.fromString(arguments[1]);
                    tasks.submit(player, request, () -> database.receipt(id, receipt));
                }
                case "economy" -> {
                    if (!player.hasPermission("cuuvuc.admin")) throw new IllegalArgumentException("Bạn không có quyền cuuvuc.admin.");
                    tasks.submit(player, request, database::economy);
                }
                case "minebuild" -> {
                    if (!player.hasPermission("cuuvuc.admin")) throw new IllegalArgumentException("Bạn không có quyền cuuvuc.admin.");
                    mine.build(player);
                }
                case "craft" -> {
                    requireThree(arguments); String recipe = arguments[1]; long count = Domain.quantity(arguments[2]);
                    Domain.recipe(catalog, recipe);
                    tasks.submit(player, request, () -> database.craft(request, id, name, recipe, count));
                }
                case "sell" -> {
                    requireThree(arguments); String resource = arguments[1]; long count = Domain.quantity(arguments[2]);
                    Domain.resource(catalog, resource);
                    tasks.submit(player, request, () -> database.sell(request, id, name, resource, count));
                }
                case "buy" -> {
                    requireThree(arguments); String resource = arguments[1]; long count = Domain.quantity(arguments[2]);
                    Domain.resource(catalog, resource);
                    tasks.submit(player, request, () -> database.buy(request, id, name, resource, count));
                }
                case "pay" -> {
                    requireThree(arguments); UUID target = UUID.fromString(arguments[1]); long count = Domain.quantity(arguments[2]);
                    tasks.submit(player, request, () -> database.pay(request, id, name, target, count));
                }
                default -> throw new IllegalArgumentException("Lệnh không hợp lệ. Dùng /cv help.");
            }
        } catch (IllegalArgumentException failure) { player.sendPlainMessage(failure.getMessage()); }
        return true;
    }

    private static void requireThree(String[] arguments) {
        if (arguments.length != 3) throw new IllegalArgumentException("Cần đúng 2 tham số. Dùng /cv help.");
    }

    private boolean isReady() { return state.get() == State.READY; }

    @Override public void onDisable() {
        state.set(State.STOPPED);
        Map<UUID, UUID> pending = tasks == null ? Map.of() : tasks.stop();
        Bukkit.getAsyncScheduler().cancelTasks(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        if (database != null) {
            GameDatabase.DrainResult result = database.drainAndClose(shutdownTimeout);
            saveShutdownReport(pending, result);
        }
    }

    /** Lưu request chưa phản hồi để tra receipt; không khẳng định request đã commit hay tự phát lại. */
    private void saveShutdownReport(Map<UUID, UUID> pending, GameDatabase.DrainResult result) {
        Properties report = new Properties();
        report.setProperty("drained", Boolean.toString(result.drained()));
        pending.forEach((player, request) -> report.setProperty("request." + request, player.toString()));
        for (int index = 0; index < result.pending().size(); index++) report.setProperty("database." + index, result.pending().get(index));
        Path target = getDataFolder().toPath().resolve("shutdown-pending.properties");
        Path temporary = getDataFolder().toPath().resolve("shutdown-pending.properties.tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                report.store(writer, "Yêu cầu cần đối chiếu bằng receipt sau khi khởi động lại");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new IllegalStateException("Không lưu được báo cáo shutdown tại " + target, failure); }
    }
}
