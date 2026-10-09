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
    @FunctionalInterface
    public interface ViewAction { Domain.PlayerView execute() throws SQLException; }
    @FunctionalInterface
    public interface ViewReply { void apply(Domain.PlayerView view); }

    private final JavaPlugin plugin;
    private final Semaphore capacity;
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final Map<UUID, Domain.PlayerView> views = new ConcurrentHashMap<>();
    private final Map<UUID, ViewReply> callbacks = new ConcurrentHashMap<>();

    public PlayerTasks(JavaPlugin plugin, int maximum) {
        if (maximum < 1 || maximum > 128) throw new IllegalArgumentException("max-pending phải từ 1 đến 128.");
        this.plugin = plugin;
        this.capacity = new Semaphore(maximum);
    }

    /** Giữ dữ liệu I/O riêng; chỉ callback trên scheduler entity mới đọc hoặc sửa inventory. */
    public synchronized void submitView(Player player, UUID request, ViewAction action, ViewReply callback) {
        if (pending.containsKey(player.getUniqueId()) || !accepting.get()) {
            player.sendPlainMessage("Thao tác trước đang xử lý hoặc máy chủ đang dừng."); return;
        }
        callbacks.put(request, callback);
        boolean accepted = submit(player, request, () -> {
            Domain.PlayerView view = action.execute();
            views.put(request, view);
            return view.message();
        });
        if (!accepted) callbacks.remove(request);
    }

    public synchronized boolean submit(Player player, UUID request, DatabaseAction action) {
        UUID playerId = player.getUniqueId();
        if (!accepting.get()) { player.sendPlainMessage("Cửu Vực đang dừng."); return false; }
        if (pending.putIfAbsent(playerId, request) != null) { player.sendPlainMessage("Giao dịch trước đang xử lý."); return false; }
        if (!capacity.tryAcquire()) {
            pending.remove(playerId);
            player.sendPlainMessage("Máy chủ đang bận, vui lòng thử lại.");
            return false;
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
                if (!accepting.get()) { views.remove(request); callbacks.remove(request); }
            }
          });
        } catch (IllegalPluginAccessException failure) {
            pending.remove(playerId);
            capacity.release();
            plugin.getSLF4JLogger().atWarn().addKeyValue("request", request).setCause(failure).log("request_not_scheduled_plugin_stopping");
            return false;
        }
        return true;
    }

    private synchronized void reply(Player player, UUID playerId, UUID request, String message) {
        if (!accepting.get()) return;
        try {
          player.getScheduler().run(plugin, task -> {
                Domain.PlayerView view = views.remove(request);
                ViewReply callback = callbacks.remove(request);
                player.sendPlainMessage(message);
                if (view != null && callback != null) callback.apply(view);
            }, () -> {
                views.remove(request); callbacks.remove(request);
                plugin.getSLF4JLogger().atInfo().addKeyValue("player", playerId)
                        .addKeyValue("request", request).log("player_retired_before_reply");
            });
        } catch (IllegalPluginAccessException failure) {
            views.remove(request); callbacks.remove(request);
            plugin.getSLF4JLogger().atWarn().addKeyValue("request", request).setCause(failure).log("reply_not_scheduled_plugin_stopping");
        }
    }

    public synchronized Map<UUID, UUID> stop() {
        accepting.set(false);
        views.clear(); callbacks.clear();
        pending.forEach((player, request) -> plugin.getSLF4JLogger().atWarn()
                .addKeyValue("player", player).addKeyValue("request", request).log("request_pending_at_shutdown"));
        return Map.copyOf(pending);
    }
}
