package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Hủy phá vanilla, commit phần thưởng rồi mới ẩn khối và tái tạo bằng scheduler vùng.
 * Cooldown và phần thưởng cùng transaction; restart dựng lại khối, cooldown DB vẫn giữ.
 * Listener đọc khối tại vùng phát sự kiện; tác vụ dựng mỏ chạy bằng RegionScheduler.
 */
public final class MineNodes implements Listener {
    public record Node(int x, int y, int z, String resource) { }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final PhysicalItems items;
    private final Domain.Catalog catalog;
    private final BooleanSupplier ready;
    private final String worldName;
    private final List<Node> nodes;
    private final int cooldown;

    public MineNodes(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, PhysicalItems items, Domain.Catalog catalog,
                     BooleanSupplier ready, String worldName, List<String> definitions, int cooldown) {
        if (cooldown < 1 || cooldown > 3600) throw new IllegalArgumentException("cooldown-seconds phải từ 1 đến 3600.");
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.items = items; this.catalog = catalog;
        this.ready = ready; this.worldName = worldName; this.cooldown = cooldown;
        this.nodes = definitions.stream().map(MineNodes::parse).toList();
        if (nodes.isEmpty() || nodes.stream().map(node -> node.x() + ":" + node.y() + ":" + node.z()).distinct().count() != nodes.size())
            throw new IllegalArgumentException("Danh sách điểm mỏ rỗng hoặc trùng tọa độ.");
        for (Node node : nodes) {
            if (worldName.equals(PlayableWorld.PRISON) && (node.x() < 0 || node.x() > 15 || node.z() < 0 || node.z() > 15 || node.y() != 100))
                throw new IllegalArgumentException("Mạch Prison phải nằm trong chunk (0,0), tại y=100: " + node);
            Domain.Resource resource = Domain.resource(catalog, node.resource());
            if (resource.xp() <= 0 || !Material.valueOf(resource.block()).isBlock())
                throw new IllegalArgumentException("Tài nguyên mỏ không hợp lệ: " + node.resource());
        }
    }

    private static Node parse(String value) {
        String[] fields = value.split(",", -1);
        if (fields.length != 4) throw new IllegalArgumentException("Điểm mỏ cần x,y,z,resource: " + value);
        return new Node(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3]);
    }

    private Optional<Node> node(Block block) {
        if (!block.getWorld().getName().equals(worldName)) return Optional.empty();
        return nodes.stream().filter(node -> node.x() == block.getX() && node.y() == block.getY() && node.z() == block.getZ()).findFirst();
    }

    public boolean handles(Block block) { return node(block).isPresent(); }

    public void buildWorld(World world) {
        if (!world.getName().equals(worldName)) throw new IllegalArgumentException("Sai world mỏ: " + world.getName());
        for (Node node : nodes) world.getBlockAt(node.x(), node.y(), node.z()).setType(Material.valueOf(Domain.resource(catalog, node.resource()).block()), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mine(BlockBreakEvent event) {
        Optional<Node> match = node(event.getBlock());
        if (match.isEmpty()) return;
        event.setCancelled(true);
        harvest(event.getPlayer(), event.getBlock(), match.orElseThrow());
    }

    /** Một cú chạm cuốc vào mạch riêng thay thao tác giữ chuột; cooldown/receipt vẫn là quyền DB. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void touch(BlockDamageEvent event) {
        Optional<Node> match = node(event.getBlock());
        if (match.isEmpty()) return;
        event.setCancelled(true);
        harvest(event.getPlayer(), event.getBlock(), match.orElseThrow());
    }

    private void harvest(Player player, Block block, Node node) {
        if (!ready.getAsBoolean()) { player.sendPlainMessage("Cơ sở dữ liệu chưa sẵn sàng."); return; }
        if (!player.hasPermission("cuuvuc.play") || player.getGameMode() != GameMode.SURVIVAL) return;
        Domain.Resource resource = Domain.resource(catalog, node.resource());
        if (block.getType() != Material.valueOf(resource.block())) return;
        if (!player.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) {
            player.sendPlainMessage("Cần dùng cuốc để khai thác mạch quặng."); return;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String nodeId = block.getWorld().getUID() + ":" + node.x() + ":" + node.y() + ":" + node.z();
        UUID request = UUID.randomUUID();
        Location location = block.getLocation();
        tasks.submitView(player, request, () -> database.view(playerId, database.mine(request, playerId, playerName, nodeId, resource.id(), cooldown)), view -> {
            items.sync(player, view);
            if (view.message().startsWith("+1")) {
                Bukkit.getRegionScheduler().execute(plugin, location, () -> {
                    location.getBlock().setType(Material.AIR, false);
                    Bukkit.getRegionScheduler().runDelayed(plugin, location, task -> location.getBlock().setType(Material.valueOf(resource.block()), false), cooldown * 20L);
                });
            }
        });
    }

    public void build(Player player) {
        World world = player.getWorld();
        if (!world.getName().equals(worldName)) throw new IllegalArgumentException("Hãy vào thế giới " + worldName);
        for (Node node : nodes) {
            if (node.y() < world.getMinHeight() || node.y() >= world.getMaxHeight())
                throw new IllegalArgumentException("Điểm mỏ ngoài chiều cao thế giới: " + node);
        }
        for (Node node : nodes) {
            Location location = new Location(world, node.x(), node.y(), node.z());
            Material material = Material.valueOf(Domain.resource(catalog, node.resource()).block());
            Bukkit.getRegionScheduler().execute(plugin, location, () -> {
                Block block = location.getBlock();
                if (!block.getType().isAir() && block.getType() != material) {
                    plugin.getSLF4JLogger().atWarn().addKeyValue("node", node).log("mine_build_block_occupied");
                    player.getScheduler().run(plugin, task -> player.sendPlainMessage("Không ghi đè khối tại " + node), null);
                    return;
                }
                block.setType(material, false);
                player.getScheduler().run(plugin, task -> player.sendPlainMessage("Điểm mỏ sẵn sàng: " + node), null);
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void place(BlockPlaceEvent event) { if (node(event.getBlock()).isPresent()) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) { if (event.blockList().stream().anyMatch(block -> node(block).isPresent())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void explodeBlock(BlockExplodeEvent event) { if (event.blockList().stream().anyMatch(block -> node(block).isPresent())) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void change(EntityChangeBlockEvent event) { if (node(event.getBlock()).isPresent()) event.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> node(block).isPresent() || node(block.getRelative(event.getDirection())).isPresent())) event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> node(block).isPresent() || node(block.getRelative(event.getDirection())).isPresent())) event.setCancelled(true);
    }
}
