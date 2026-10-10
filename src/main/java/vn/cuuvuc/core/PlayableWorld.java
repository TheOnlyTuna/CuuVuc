package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Mỗi động phủ nằm trọn trong một chunk, có slot DB riêng và quyền mặc định từ chối.
 * Nền được dựng trước khi chuyển ready; restart chỉ dựng lại đảo chưa ready.
 * Đây là connector Paper; chưa công bố hỗ trợ tạo world trên runtime Folia.
 */
public final class PlayableWorld implements Listener {
    public static final String PRISON = "cv_prison";
    public static final String SKY = "cv_sky";
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final MineNodes mine;
    private final BooleanSupplier ready;
    private final ContractWorld contracts;
    private final AtomicReference<Domain.Claims> claims = new AtomicReference<>(new Domain.Claims(-1, java.util.List.of()));
    private final Map<UUID, Boolean> building = new ConcurrentHashMap<>();
    private World prison;
    private World sky;
    private volatile boolean prisonReady;

    public PlayableWorld(JavaPlugin plugin, GameDatabase database, MineNodes mine, BooleanSupplier ready, ContractWorld contracts) {
        this.plugin = plugin; this.database = database; this.mine = mine; this.ready = ready; this.contracts = contracts;
    }

    public void create() {
        prison = createWorld(PRISON);
        sky = createWorld(SKY);
        prison.setSpawnLocation(8, 100, 4);
        sky.setSpawnLocation(8, 100, 8);
        Bukkit.getRegionScheduler().execute(plugin, new Location(prison, 8, 100, 8), () -> {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) prison.getBlockAt(x, 99, z).setType(Material.STONE_BRICKS, false);
            prison.getBlockAt(2, 100, 3).setType(Material.CRAFTING_TABLE, false);
            prison.getBlockAt(13, 100, 3).setType(Material.BARREL, false);
            mine.buildWorld(prison);
            prisonReady = true;
        });
    }

    private World createWorld(String name) {
        boolean existed = new java.io.File(plugin.getServer().getWorldContainer(), name).exists();
        World world = java.util.Objects.requireNonNull(new WorldCreator(name).generator(new VoidWorldGenerator()).generateStructures(false).createWorld());
        NamespacedKey marker = new NamespacedKey(plugin, "managed_world");
        String stored = world.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (existed && !name.equals(stored)) throw new IllegalStateException("World " + name + " đã tồn tại nhưng không thuộc Cửu Vực. Đổi tên world đó sau khi backup trước khi chạy V0.6.");
        world.getPersistentDataContainer().set(marker, PersistentDataType.STRING, name);
        return world;
    }

    public void publish(Domain.Claims next) {
        claims.updateAndGet(previous -> next.revision() >= previous.revision() ? next : previous);
    }

    public java.util.Optional<Domain.Island> ownerIsland(UUID owner) {
        return claims.get().islands().stream().filter(island -> island.owner().equals(owner)).findFirst();
    }

    private static boolean managed(World world) { return world.getName().equals(PRISON) || world.getName().equals(SKY); }

    public void prison(Player player) {
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return; }
        if (!ready.getAsBoolean() || !prisonReady) { player.sendPlainMessage("Prison chưa sẵn sàng."); return; }
        teleport(player, new Location(prison, 8.5, 100, 4.5));
    }

    public void enterPrisonFromMenu(Player player) {
        player.getScheduler().run(plugin, task -> { player.closeInventory(); prison(player); }, () -> plugin.getSLF4JLogger().info("menu_player_retired"));
    }

    public void enterHomeFromMenu(Player player) {
        player.getScheduler().run(plugin, task -> { player.closeInventory(); home(player); }, () -> plugin.getSLF4JLogger().info("menu_player_retired"));
    }

    private void teleport(Player player, Location location) {
        UUID id = player.getUniqueId();
        player.teleportAsync(location).whenComplete((success, failure) -> {
            if (failure != null) plugin.getSLF4JLogger().atError().addKeyValue("player", id).setCause(failure).log("world_teleport_failed");
            player.getScheduler().run(plugin, task -> {
                if (failure != null || !success) player.sendPlainMessage("Không thể dịch chuyển; xem log server và thử lại.");
            }, () -> plugin.getSLF4JLogger().info("teleport_player_retired"));
        });
    }

    public void home(Player player) {
        if (!player.hasPermission("cuuvuc.play")) { player.sendPlainMessage("Bạn không có quyền cuuvuc.play."); return; }
        if (!ready.getAsBoolean()) { player.sendPlainMessage("Động phủ chưa sẵn sàng."); return; }
        UUID owner = player.getUniqueId(); String name = player.getName();
        if (building.putIfAbsent(owner, true) != null) { player.sendPlainMessage("Động phủ đang được chuẩn bị."); return; }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                Domain.Island island = database.reserveIsland(owner, name);
                publish(database.islands());
                Location destination = new Location(sky, island.slot() * 64 + 8.5, 100, 8.5);
                if (island.ready()) { finishHome(player, owner, destination); return; }
                Bukkit.getRegionScheduler().execute(plugin, destination, () -> buildIsland(player, island, destination));
            } catch (SQLException failure) {
                building.remove(owner);
                plugin.getSLF4JLogger().atError().addKeyValue("owner", owner).setCause(failure).log("island_reserve_failed");
                message(player, "Không cấp được động phủ; dữ liệu chưa thay đổi ngoài DB. Xem log server.");
            }
        });
    }

    public void visit(Player player, UUID owner) {
        if (!player.hasPermission("cuuvuc.play") || !ready.getAsBoolean()) { player.sendPlainMessage("Bạn chưa thể đến động phủ."); return; }
        Domain.Island island = claims.get().islands().stream().filter(candidate -> candidate.owner().equals(owner) && candidate.ready()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Động phủ chưa sẵn sàng hoặc không tồn tại: " + owner));
        teleport(player, new Location(sky, island.slot() * 64 + 8.5, 100, 8.5));
    }

    private void buildIsland(Player player, Domain.Island island, Location destination) {
        int center = island.slot() * 64 + 8;
        for (int x = center - 4; x <= center + 4; x++) for (int z = 4; z <= 12; z++) {
            sky.getBlockAt(x, 98, z).setType(Material.DIRT, false);
            sky.getBlockAt(x, 99, z).setType(Material.GRASS_BLOCK, false);
        }
        sky.getBlockAt(center - 2, 100, 8).setType(Material.OAK_SAPLING, false);
        sky.getBlockAt(center + 2, 100, 8).setType(Material.CHEST, false);
        Chest chest = (Chest) sky.getBlockAt(center + 2, 100, 8).getState();
        chest.getInventory().setItem(0, new ItemStack(Material.OAK_LOG, 16));
        chest.getInventory().setItem(1, new ItemStack(Material.DIRT, 32));
        chest.getInventory().setItem(2, new ItemStack(Material.WATER_BUCKET));
        chest.getInventory().setItem(3, new ItemStack(Material.LAVA_BUCKET));
        chest.getInventory().setItem(4, new ItemStack(Material.WOODEN_PICKAXE));
        // Paper lưu world trước khi DB cấp quyền; đảo chưa ready không thể lấy đồ hoặc xây dựng.
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            sky.save();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try { publish(database.islandReady(island.owner())); finishHome(player, island.owner(), destination); }
                catch (SQLException failure) {
                    building.remove(island.owner());
                    plugin.getSLF4JLogger().atError().addKeyValue("owner", island.owner()).setCause(failure).log("island_ready_failed");
                    message(player, "Động phủ chưa xác nhận sẵn sàng; lần sau sẽ kiểm tra lại.");
                }
            });
        });
    }

    private void finishHome(Player player, UUID owner, Location destination) {
        building.remove(owner);
        player.getScheduler().run(plugin, task -> teleport(player, destination), () -> plugin.getSLF4JLogger().info("island_owner_retired"));
    }

    private void message(Player player, String text) {
        try { player.getScheduler().run(plugin, task -> player.sendPlainMessage(text), () -> plugin.getSLF4JLogger().info("world_reply_retired")); }
        catch (IllegalPluginAccessException failure) { plugin.getSLF4JLogger().atWarn().setCause(failure).log("world_reply_after_shutdown"); }
    }

    public void invite(Player player, UUID builder) {
        UUID owner = player.getUniqueId();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { publish(database.inviteBuilder(owner, builder)); message(player, "Đã cấp quyền xây dựng động phủ cho " + builder); }
            catch (SQLException | IllegalArgumentException failure) { plugin.getSLF4JLogger().atWarn().setCause(failure).log("island_invite_failed"); message(player, "Không cấp được quyền: người nhận phải có hồ sơ, bạn phải có động phủ."); }
        });
    }

    public void revoke(Player player, UUID builder) {
        UUID owner = player.getUniqueId();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { publish(database.revokeBuilder(owner, builder)); message(player, "Đã thu hồi quyền xây dựng của " + builder); }
            catch (SQLException failure) { plugin.getSLF4JLogger().atError().setCause(failure).log("island_revoke_failed"); message(player, "Không thu hồi được quyền; xem log server."); }
        });
    }

    private boolean allowed(Player player, Block block) {
        if (!ready.getAsBoolean() || !block.getWorld().getName().equals(SKY)) return false;
        boolean islandPermission = Domain.islandAt(claims.get().islands(), block.getX(), block.getZ())
                .map(island -> Domain.canBuild(island, player.getUniqueId())).orElse(false);
        return contracts.permission(player.getUniqueId(), block, islandPermission);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) public void breakBlock(BlockBreakEvent event) {
        if (!managed(event.getBlock().getWorld())) return;
        if (event.getBlock().getWorld().getName().equals(PRISON) && mine.handles(event.getBlock())) return;
        if (!allowed(event.getPlayer(), event.getBlock())) { event.setCancelled(true); event.getPlayer().sendPlainMessage("Bạn không có quyền xây dựng tại đây."); }
    }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) public void place(BlockPlaceEvent event) {
        if (managed(event.getBlock().getWorld()) && !allowed(event.getPlayer(), event.getBlock())) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) public void interact(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        if (block.getWorld().getName().equals(SKY) && !allowed(event.getPlayer(), block)
                && !(ready.getAsBoolean() && event.getAction().isRightClick()
                && contracts.placementSupport(event.getPlayer().getUniqueId(), block, block.getRelative(event.getBlockFace())))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void entityInteract(PlayerInteractEntityEvent event) {
        Block block = event.getRightClicked().getLocation().getBlock();
        if (managed(block.getWorld()) && !allowed(event.getPlayer(), block)) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void playerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && managed(event.getEntity().getWorld())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void entityDamage(EntityDamageByEntityEvent event) {
        Block block = event.getEntity().getLocation().getBlock();
        if (managed(block.getWorld()) && (!(event.getDamager() instanceof Player player) || !allowed(player, block))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void hangingPlace(HangingPlaceEvent event) {
        if (managed(event.getBlock().getWorld()) && (contracts.at(event.getBlock()).isPresent() || event.getPlayer() == null || !allowed(event.getPlayer(), event.getBlock()))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void hangingBreak(HangingBreakEvent event) {
        if (managed(event.getEntity().getWorld())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void emptyBucket(PlayerBucketEmptyEvent event) {
        Block target = event.getBlockClicked().getRelative(event.getBlockFace());
        if (managed(target.getWorld()) && (contracts.at(target).isPresent() || !allowed(event.getPlayer(), target))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void fillBucket(PlayerBucketFillEvent event) {
        if (managed(event.getBlock().getWorld()) && (contracts.at(event.getBlock()).isPresent() || !allowed(event.getPlayer(), event.getBlock()))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void piston(BlockPistonExtendEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void retract(BlockPistonRetractEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void explode(EntityExplodeEvent event) { if (managed(event.getLocation().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void blockExplode(BlockExplodeEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void entityChange(EntityChangeBlockEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void burn(BlockBurnEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void ignite(BlockIgniteEvent event) { if (managed(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void flow(BlockFromToEvent event) {
        if (!managed(event.getBlock().getWorld())) return;
        java.util.Optional<Domain.Island> from = Domain.islandAt(claims.get().islands(), event.getBlock().getX(), event.getBlock().getZ());
        java.util.Optional<Domain.Island> to = Domain.islandAt(claims.get().islands(), event.getToBlock().getX(), event.getToBlock().getZ());
        if (contracts.at(event.getToBlock()).isPresent() || event.getBlock().getWorld().getName().equals(PRISON) || from.isEmpty() || to.isEmpty() || !from.get().ready() || !from.get().owner().equals(to.get().owner())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void grow(StructureGrowEvent event) {
        if (!managed(event.getWorld())) return;
        java.util.Optional<Domain.Island> island = Domain.islandAt(claims.get().islands(), event.getLocation().getBlockX(), event.getLocation().getBlockZ());
        if (!event.getWorld().getName().equals(SKY) || island.isEmpty() || !island.get().ready() || event.getBlocks().stream().anyMatch(block -> contracts.at(block.getBlock()).isPresent() || Domain.islandAt(claims.get().islands(), block.getX(), block.getZ()).map(candidate -> !candidate.owner().equals(island.get().owner())).orElse(true))) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true) public void portal(PlayerPortalEvent event) { if (managed(event.getFrom().getWorld())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void voidFall(PlayerMoveEvent event) {
        if (managed(event.getPlayer().getWorld()) && event.getTo().getY() < 50 && event.getFrom().getY() >= 50) prison(event.getPlayer());
    }
}
