package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cổng scheduler: giới hạn một thao tác đang xử lý mỗi UUID và giới hạn toàn máy chủ.
 * Lambda I/O chỉ nhận dữ liệu đã chụp; không đọc thế giới hay inventory trên luồng async.
 * Phản hồi chuyển về scheduler của entity; đăng xuất không hủy giao dịch đã nhận.
 * Khi dừng, kết quả vẫn bền trong DB nhưng không gửi tác vụ mới cho entity đã nghỉ.
 */
public final class PlayerTasks {
    @FunctionalInterface
    public interface DatabaseAction { String execute() throws SQLException; }

    private final JavaPlugin plugin;
    private final Semaphore capacity;
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public PlayerTasks(JavaPlugin plugin, int maximum) {
        if (maximum < 1 || maximum > 128) throw new IllegalArgumentException("max-pending phải từ 1 đến 128.");
        this.plugin = plugin;
        this.capacity = new Semaphore(maximum);
    }

    public synchronized void submit(Player player, UUID request, DatabaseAction action) {
        UUID playerId = player.getUniqueId();
        if (!accepting.get()) { player.sendPlainMessage("Cửu Vực đang dừng."); return; }
        if (pending.putIfAbsent(playerId, request) != null) { player.sendPlainMessage("Giao dịch trước đang xử lý."); return; }
        if (!capacity.tryAcquire()) {
            pending.remove(playerId);
            player.sendPlainMessage("Máy chủ đang bận, vui lòng thử lại.");
            return;
        }
        plugin.getSLF4JLogger().atInfo().addKeyValue("player", playerId).addKeyValue("request", request).log("player_request_accepted");
        try {
          Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                String result = action.execute();
                reply(player, playerId, request, result);
            } catch (IllegalArgumentException | ArithmeticException failure) {
                reply(player, playerId, request, failure.getMessage());
            } catch (SQLException failure) {
                plugin.getSLF4JLogger().atError().addKeyValue("player", playerId).addKeyValue("request", request)
                        .addKeyValue("sql_state", failure.getSQLState()).setCause(failure).log("player_database_action_failed");
                reply(player, playerId, request, "Chưa xác nhận được giao dịch. Xem /cv profile và /cv ledger trước khi thử lại. Mã: " + request);
            } finally {
                pending.remove(playerId);
                capacity.release();
            }
          });
        } catch (IllegalPluginAccessException failure) {
            pending.remove(playerId);
            capacity.release();
            plugin.getSLF4JLogger().atWarn().addKeyValue("request", request).setCause(failure).log("request_not_scheduled_plugin_stopping");
        }
    }

    private synchronized void reply(Player player, UUID playerId, UUID request, String message) {
        if (!accepting.get()) return;
        try {
          player.getScheduler().run(plugin, task -> player.sendPlainMessage(message),
                () -> plugin.getSLF4JLogger().atInfo().addKeyValue("player", playerId)
                        .addKeyValue("request", request).log("player_retired_before_reply"));
        } catch (IllegalPluginAccessException failure) {
            plugin.getSLF4JLogger().atWarn().addKeyValue("request", request).setCause(failure).log("reply_not_scheduled_plugin_stopping");
        }
    }

    public synchronized Map<UUID, UUID> stop() {
        accepting.set(false);
        pending.forEach((player, request) -> plugin.getSLF4JLogger().atWarn()
                .addKeyValue("player", player).addKeyValue("request", request).log("request_pending_at_shutdown"));
        return Map.copyOf(pending);
    }
}
