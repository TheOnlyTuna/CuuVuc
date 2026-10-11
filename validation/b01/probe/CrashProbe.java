package vn.cuuvuc.validation;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import vn.cuuvuc.core.ContractWorld;
import vn.cuuvuc.core.GameDatabase;

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

/** Plugin chỉ dành runtime B01: tiêm thay đổi vào queue thật, không giả làm thao tác client. */
public final class CrashProbe extends JavaPlugin {
    private static final UUID CONTRACT = UUID.fromString("279bbdf9-0fe9-4eec-8bb6-46a9df8c72a3");
    private static final UUID BUILDER = UUID.fromString("3bb5ddbc-5b75-377f-8647-6f25c51f252a");
    private static final UUID OWNER = UUID.fromString("f3d83b55-4452-39bc-85f4-29f8cebdcc4f");
    private JavaPlugin core;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    @Override public void onEnable() {
        core = (JavaPlugin) Bukkit.getPluginManager().getPlugin("CuuVucCore");
        if (core == null || !getServer().getWorldContainer().getAbsolutePath().contains("run-b01-"))
            throw new IllegalStateException("CrashProbe chỉ cho phép runtime riêng run-b01-*");
        Bukkit.getAsyncScheduler().runAtFixedRate(this, task -> poll(), 1, 100, TimeUnit.MILLISECONDS);
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
        } catch (IOException | ReflectiveOperationException | SQLException failure) {
            busy.set(false); getSLF4JLogger().atError().setCause(failure).log("B01_PROBE_FAILED");
        }
    }

    private void execute(String id, String action) throws ReflectiveOperationException, SQLException, IOException {
        GameDatabase database = (GameDatabase) field("database");
        ContractWorld contracts = (ContractWorld) field("contracts");
        Field refreshing = ContractWorld.class.getDeclaredField("refreshing"); refreshing.setAccessible(true);
        switch (action) {
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

    private void done(String id, String result) throws IOException {
        Files.writeString(getDataFolder().toPath().resolve(id + ".done"), result, StandardCharsets.UTF_8);
        getSLF4JLogger().atInfo().addKeyValue("step", id).addKeyValue("result", result).log("B01_PROBE_DONE");
        busy.set(false);
    }
}
