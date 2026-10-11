package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
    private PhysicalItems items;
    private PlayableWorld world;
    private PlayableMenu menu;
    private ContractWorld contracts;
    private ContractMenu contractMenu;
    private MaterialTransfers transfers;
    private SectMenu sects;
    private TreasuryMenu treasury;
    private ProjectMenu projects;
    private boolean contractAuditSaved;
    private Duration shutdownTimeout;

    @Override public void onEnable() {
        saveDefaultConfig();
        long timeoutMillis = getConfig().getLong("shutdown.drain-timeout-ms");
        if (timeoutMillis < 1 || timeoutMillis > 30000) throw new IllegalStateException("Cần cấu hình shutdown.drain-timeout-ms từ 1 đến 30000.");
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
        items = new PhysicalItems(this, catalog);
        if (!PlayableWorld.PRISON.equals(getConfig().getString("mine.world"))) throw new IllegalStateException("V0.6 cần mine.world=cv_prison và danh sách node trong chunk (0,0). Cập nhật config theo mẫu trước khi chạy.");
        mine = new MineNodes(this, database, tasks, items, catalog, this::isReady,
                Objects.requireNonNull(getConfig().getString("mine.world")), getConfig().getStringList("mine.nodes"),
                getConfig().getInt("mine.cooldown-seconds"));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(mine, this);
        contracts = new ContractWorld(this, database, tasks);
        world = new PlayableWorld(this, database, mine, this::isReady, contracts);
        contractMenu = new ContractMenu(this, database, tasks, contracts, world);
        menu = new PlayableMenu(database, tasks, items, catalog, world, contractMenu);
        transfers = new MaterialTransfers(this, database, tasks, items, catalog);
        sects = new SectMenu(this, database, tasks);
        treasury = new TreasuryMenu(this, database, tasks, contracts, world);
        getServer().getPluginManager().registerEvents(treasury, this);
        projects = new ProjectMenu(this, database, tasks, items, contracts);
        getServer().getPluginManager().registerEvents(projects, this);
        getServer().getPluginManager().registerEvents(sects, this);
        getServer().getPluginManager().registerEvents(transfers, this);
        getServer().getPluginManager().registerEvents(items, this);
        getServer().getPluginManager().registerEvents(world, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(contracts, this);
        getServer().getPluginManager().registerEvents(contractMenu, this);
        world.create();
        Objects.requireNonNull(getCommand("cv")).setExecutor(this);
        Bukkit.getAsyncScheduler().runNow(this, task -> {
            try {
                database.initialize();
                contracts.recoverAudit();
                world.publish(database.islands());
                database.expireContracts();
                contracts.publish(database.contractBoard());
                contracts.start();
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
        else event.getPlayer().sendPlainMessage("Cửu Vực đang khởi động. Dùng /cv start sau ít giây.");
    }

    private void showProfile(Player player) {
        UUID id = player.getUniqueId();
        UUID request = UUID.randomUUID();
        tasks.submit(player, request, () -> database.profile(id));
    }

    private void register(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        tasks.submitView(player, UUID.randomUUID(), () -> database.view(id, database.register(id, name) + " /cv để mở menu."), view -> items.sync(player, view));
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        if (!isReady()) return;
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(this, task -> register(player), () -> getSLF4JLogger().info("respawn_player_retired"), 1L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player player)) { sender.sendMessage("CuuVucCore " + getPluginMeta().getVersion() + " | database-ready=" + isReady()); return true; }
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return true; }
        if (!isReady()) { player.sendPlainMessage("Cơ sở dữ liệu chưa sẵn sàng."); return true; }
        if (arguments.length == 0) { menu.request(player); return true; }
        if (arguments[0].equals("help")) {
            player.sendPlainMessage("/cv treasury | contribute/withdraw/hire <số> | grant/revoke <UUID> (dùng /cv treasury)");
            player.sendPlainMessage("/cv sect | sect create <tên> | invite/kick <UUID> | accept/cancel <UUID-lời-mời> | role <UUID> <ELDER/MEMBER> | leave (các thao tác dùng /cv sect)");
            player.sendPlainMessage("/cv menu | prison | home | visit/invite/revoke <UUID> | start | profile | kho | catalog | craft <recipe> <1..1000> | buy/sell <resource> <1..1000> | pay <UUID> <1..1000> | transfer <UUID> <resource> <1..1000> | materials | ledger | receipt <UUID> | economy (OP)");
            return true;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        UUID request = UUID.randomUUID();
        try {
            switch (arguments[0]) {
                case "sect" -> sects.command(player, arguments);
                case "treasury" -> treasury.command(player, arguments);
                case "projects" -> projects.command(player, arguments);
                case "start" -> register(player);
                case "profile" -> showProfile(player);
                case "menu", "kho" -> menu.request(player);
                case "contracts" -> contractMenu.open(player, 0);
                case "prison" -> world.prison(player);
                case "home" -> world.home(player);
                case "visit" -> {
                    if (arguments.length != 2) throw new IllegalArgumentException("Dùng /cv visit <UUID-chủ-động-phủ>.");
                    world.visit(player, UUID.fromString(arguments[1]));
                }
                case "invite", "revoke" -> {
                    if (arguments.length != 2) throw new IllegalArgumentException("Dùng /cv invite <UUID> hoặc /cv revoke <UUID>.");
                    UUID builder = UUID.fromString(arguments[1]);
                    if (arguments[0].equals("invite")) world.invite(player, builder);
                    else world.revoke(player, builder);
                }
                case "catalog" -> {
                    for (Domain.Resource resource : catalog.resources().values()) player.sendPlainMessage(resource.id() + " | " + resource.name() + " | mua=" + resource.buy() + " bán=" + resource.sell());
                    for (Domain.Recipe recipe : catalog.recipes().values()) player.sendPlainMessage(recipe.id() + " | " + recipe.inputCount() + " " + recipe.input() + " → " + recipe.outputCount() + " " + recipe.output());
                    player.sendPlainMessage("UUID của bạn: " + id);
                }
                case "ledger" -> tasks.submit(player, request, () -> database.history(id));
                case "materials" -> tasks.submit(player, request, () -> database.materialHistory(id));
                case "transfer" -> {
                    if (arguments.length != 4) throw new IllegalArgumentException("Dùng /cv transfer <UUID-người-nhận> <resource> <1..1000>.");
                    transfers.request(player, UUID.fromString(arguments[1]), arguments[2], Domain.quantity(arguments[3]));
                }
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
                    tasks.submitView(player, request, () -> database.view(id, database.craft(request, id, name, recipe, count)), view -> items.sync(player, view));
                }
                case "sell" -> {
                    requireThree(arguments); String resource = arguments[1]; long count = Domain.quantity(arguments[2]);
                    Domain.resource(catalog, resource);
                    tasks.submitView(player, request, () -> database.view(id, database.sell(request, id, name, resource, count)), view -> items.sync(player, view));
                }
                case "buy" -> {
                    requireThree(arguments); String resource = arguments[1]; long count = Domain.quantity(arguments[2]);
                    Domain.resource(catalog, resource);
                    tasks.submitView(player, request, () -> database.view(id, database.buy(request, id, name, resource, count)), view -> items.sync(player, view));
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
        IOException auditFailure = null;
        if (contracts != null) {
            try { contracts.savePendingAudit(); contractAuditSaved = true; }
            catch (IOException failure) { auditFailure = failure; }
        }
        if (database != null) {
            GameDatabase.DrainResult result = database.drainAndClose(shutdownTimeout);
            saveShutdownReport(pending, result);
        }
        if (auditFailure != null) throw new IllegalStateException("Không lưu được hàng đợi lịch sử hợp đồng; không backup phiên shutdown này.", auditFailure);
    }

    /** Lưu request chưa phản hồi để tra receipt; không khẳng định request đã commit hay tự phát lại. */
    private void saveShutdownReport(Map<UUID, UUID> pending, GameDatabase.DrainResult result) {
        Properties report = new Properties();
        report.setProperty("drained", Boolean.toString(result.drained()));
        report.setProperty("contract-audit-persisted", Boolean.toString(contracts == null || contractAuditSaved));
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
