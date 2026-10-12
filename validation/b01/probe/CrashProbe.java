package vn.cuuvuc.validation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.WorldSaveEvent;
import vn.cuuvuc.core.BlockJournal;
import vn.cuuvuc.core.ContractWorld;
import vn.cuuvuc.core.GameDatabase;
import vn.cuuvuc.core.RecoveryCommands;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;

/** Plugin chỉ dành runtime B01: tiêm thay đổi vào queue thật, không giả làm thao tác client. */
public final class CrashProbe extends JavaPlugin implements Listener {
    private static final UUID CONTRACT = UUID.fromString("279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3");
    private static final UUID BUILDER = UUID.fromString("3bb5ddbc-5b75-377f-8647-6f25c51f252a");
    private static final UUID OWNER = UUID.fromString("f3d83b55-4452-39bc-85f4-29f8cebdcc4f");
    private JavaPlugin core;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean pauseSave = new AtomicBoolean(false);

    @Override public void onEnable() {
        core = (JavaPlugin) Bukkit.getPluginManager().getPlugin("CuuVucCore");
        if (core == null || !getServer().getWorldContainer().getAbsolutePath().contains("run-b01-"))
            throw new IllegalStateException("CrashProbe chỉ cho phép runtime riêng run-b01-*");
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> poll(), 1, 100, TimeUnit.MILLISECONDS);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private Object field(String name) throws ReflectiveOperationException {
        Field field = core.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(core);
    }

    private void poll() {
        Path command = getDataFolder().toPath().resolve("command.txt");
        if (!Files.exists(command) || !busy.compareAndSet(false, true)) return;
        try {
            String[] fields = Files.readString(command, StandardCharsets.UTF_8).strip().split(" ");
            if (fields.length != 2 || !fields[0].matches("[a-z0-9-]+")) throw new IOException("Lệnh probe phải là id action");
            Files.move(command, command.resolveSibling(fields[0] + ".claimed"), StandardCopyOption.ATOMIC_MOVE);
            execute(fields[0], fields[1]);
        } catch (IOException | ReflectiveOperationException | SQLException | IllegalArgumentException | IllegalStateException failure) {
            busy.set(false); getSLF4JLogger().atError().setCause(failure).log("B01_PROBE_FAILED");
        }
    }

    private void execute(String id, String action) throws ReflectiveOperationException, SQLException, IOException {
        GameDatabase database = (GameDatabase) field("database");
        ContractWorld contracts = (ContractWorld) field("contracts");
        Field refreshing = ContractWorld.class.getDeclaredField("refreshing"); refreshing.setAccessible(true);
        switch (action) {
            case "RECOVERY_GRANT", "RECOVERY_REVOKE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                String operation = action.equals("RECOVERY_GRANT") ? "grant" : "revoke";
                if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cv recovery " + operation + " " + OWNER + " Controlled isolated Paper recovery validation"))
                    throw new IllegalStateException("Không dispatch được lệnh quyền phục hồi");
                try { done(id, "CONSOLE_RECOVERY_" + operation); }
                catch (IOException failure) { throw new IllegalStateException("Không ghi kết quả quyền phục hồi", failure); }
            });
            case "RECOVERY_REVIEW" -> {
                RecoveryCommands connector = (RecoveryCommands) field("recoveryCommands");
                Method method = RecoveryCommands.class.getDeclaredMethod("review", UUID.class, UUID.class); method.setAccessible(true);
                done(id, (String) method.invoke(connector, OWNER, CONTRACT));
            }
            case "RECOVERY_RESOLVE", "RECOVERY_REJECT" -> {
                UUID review = UUID.fromString(Files.readString(getDataFolder().toPath().resolve("review-id.txt")).strip());
                RecoveryCommands connector = (RecoveryCommands) field("recoveryCommands");
                Method method = RecoveryCommands.class.getDeclaredMethod("resolve", UUID.class, UUID.class, String.class); method.setAccessible(true);
                try {
                    String result = (String) method.invoke(connector, OWNER, review, "World unchanged and intent unconfirmed; reviewed fixture evidence");
                    if (action.equals("RECOVERY_REJECT")) throw new IllegalStateException("Lẽ ra phải từ chối recovery resolve");
                    done(id, result);
                } catch (InvocationTargetException failure) {
                    if (!action.equals("RECOVERY_REJECT") || !(failure.getCause() instanceof IllegalArgumentException)) throw failure;
                    done(id, "RESOLVE_REJECTED " + failure.getCause().getMessage());
                }
            }
            case "STOP" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                try { done(id, "SOFT_STOP_REQUESTED"); }
                catch (IOException failure) { throw new IllegalStateException("Không ghi yêu cầu dừng", failure); }
                Bukkit.shutdown();
            });
            case "CLIENT_POSITION" -> {
                org.bukkit.entity.Player player = Bukkit.getPlayer(BUILDER);
                if (player == null) throw new IllegalStateException("Client C chưa online; không chuẩn bị vị trí");
                World world = java.util.Objects.requireNonNull(Bukkit.getWorld("cv_sky"));
                Bukkit.getRegionScheduler().execute(this, new Location(world,67,99,6), () -> {
                    world.getBlockAt(67,99,6).setType(Material.DIRT,false);
                    world.getBlockAt(67,99,5).setType(Material.DIRT,false);
                    player.getScheduler().run(this, task -> player.teleportAsync(new Location(world,67.5,100,6.5,180,58)).thenAccept(moved -> {
                    if (!moved) throw new IllegalStateException("Không teleport được client C");
                    try { done(id, "CLIENT_C_POSITIONED_CV_SKY_67_100_6"); }
                    catch (IOException failure) { throw new IllegalStateException("Không ghi kết quả teleport", failure); }
                    }), () -> { throw new IllegalStateException("Client C đã offline trước teleport"); });
                });
            }
            case "ARM_SAVE_CRASH" -> { pauseSave.set(true); done(id, "NATIVE_WORLD_SAVE_CALLBACK_ARMED"); }
            case "INTENT" -> {
                database.prepareBlock(new BlockJournal.Entry(UUID.randomUUID(), CONTRACT, BUILDER, "PLACE", "67:100:5", "AIR", "COBBLESTONE", BlockJournal.State.INTENT));
                done(id, "DURABLE_INTENT_ONLY");
            }
            case "FENCE_CHECK" -> {
                try { database.submitContract(CONTRACT, BUILDER); throw new IllegalStateException("Fence không chặn SUBMIT"); }
                catch (IllegalArgumentException rejected) { if (!rejected.getMessage().contains("RECOVERY_REQUIRED")) throw rejected; }
                try { database.abandonContract(CONTRACT, BUILDER); throw new IllegalStateException("Fence không chặn ABANDON"); }
                catch (IllegalArgumentException rejected) { if (!rejected.getMessage().contains("RECOVERY_REQUIRED")) throw rejected; }
                done(id, "RECOVERY_REQUIRED_SUBMIT_AND_REFUND_BLOCKED");
            }
            case "JPLACE", "JBREAK" -> {
                String event = action.equals("JPLACE") ? "PLACE" : "BREAK";
                BlockJournal.Entry entry = new BlockJournal.Entry(UUID.randomUUID(), CONTRACT, BUILDER, event, "67:100:5", event.equals("PLACE") ? "AIR" : "COBBLESTONE", event.equals("PLACE") ? "COBBLESTONE" : "AIR", BlockJournal.State.INTENT);
                database.prepareBlock(entry);
                Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                    World world = java.util.Objects.requireNonNull(Bukkit.getWorld("cv_sky"));
                    world.setAutoSave(false); world.getBlockAt(67,100,5).setType(Material.valueOf(entry.after()), false);
                    Bukkit.getAsyncScheduler().runNow(this, task -> {
                        try { database.applyBlock(entry); done(id, "INJECTED_JOURNAL_APPLIED event=" + entry.id()); }
                        catch (SQLException | IOException failure) { getSLF4JLogger().atError().setCause(failure).log("B01_JOURNAL_PROBE_FAILED"); busy.set(false); }
                    });
                });
            }
            case "HOLD" -> {
                if (!((AtomicBoolean) refreshing.get(contracts)).compareAndSet(false, true)) throw new IllegalStateException("Worker audit đang chạy; thử lại với id mới");
                done(id, "AUDIT_WORKER_HELD");
            }
            case "RESUME" -> { ((AtomicBoolean) refreshing.get(contracts)).set(false); done(id, "AUDIT_WORKER_RESUMED"); }
            case "SUBMIT" -> done(id, database.submitContract(CONTRACT, BUILDER));
            case "COMPLETE" -> done(id, database.completeContract(CONTRACT, OWNER));
            case "PLACE", "BREAK", "CLEAR", "SAVE", "OBSERVE" -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                try {
                    World world = java.util.Objects.requireNonNull(Bukkit.getWorld("cv_sky"));
                    world.setAutoSave(false);
                    Block block = world.getBlockAt(67,100,5);
                    if (action.equals("CLEAR")) block.setType(Material.AIR, false);
                    if (action.equals("PLACE") || action.equals("BREAK")) {
                        block.setType(action.equals("PLACE") ? Material.COBBLESTONE : Material.AIR, false);
                        Method record = ContractWorld.class.getDeclaredMethod("record", UUID.class, Block.class, String.class);
                        record.setAccessible(true); record.invoke(contracts, BUILDER, block, action);
                    }
                    if (action.equals("SAVE")) world.save();
                    done(id, "action=" + action + " world=" + block.getType());
                } catch (IOException | ReflectiveOperationException failure) {
                    busy.set(false); getSLF4JLogger().atError().setCause(failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure).log("B01_PROBE_FAILED");
                }
            });
            default -> throw new IllegalArgumentException("Action probe không hợp lệ: " + action);
        }
    }

    /** Chỉ fixture: giữ trong callback WorldSaveEvent thật để xác định chính xác ranh giới kill. */
    @EventHandler(priority = EventPriority.MONITOR) public void saveCheckpoint(WorldSaveEvent event) {
        if (!event.getWorld().getName().equals("cv_sky") || !pauseSave.compareAndSet(true, false)) return;
        try {
            Files.writeString(getDataFolder().toPath().resolve("native-save-entered.txt"), "WORLD_SAVE_EVENT_ENTERED_BEFORE_CHUNK_PERSISTENCE", StandardCharsets.UTF_8);
            if (!new CountDownLatch(1).await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Không hard-kill trong cửa sổ save đã định");
        } catch (IOException failure) { throw new IllegalStateException("Không ghi marker save", failure); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Save fixture bị ngắt", failure); }
    }

    private void done(String id, String result) throws IOException {
        Files.writeString(getDataFolder().toPath().resolve(id + ".done"), result, StandardCharsets.UTF_8);
        getSLF4JLogger().atInfo().addKeyValue("step", id).addKeyValue("result", result).log("B01_PROBE_DONE");
        busy.set(false);
    }
}
