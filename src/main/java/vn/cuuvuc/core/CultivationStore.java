package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Connector SQL tu luyện; bên gọi khóa hồ sơ trước tiến trình/thử thách và dùng receipt chung. */
public interface CultivationStore {
    static void verifyPolicy(Connection connection, Cultivation.Rules rules) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO cultivation_policy VALUES (true,?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, rules.signature()); statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT rules_sha256 FROM cultivation_policy WHERE singleton")) {
            if (!result.next() || !rules.signature().equals(result.getString(1)))
                throw new SQLException("Cân bằng tu luyện khác DB; triển khai chính sách có kiểm soát trước khi khởi động", "55000");
        }
    }

    static Cultivation.State state(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT rank,revision,legacy_title FROM cultivation_progress WHERE player_id=?")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Thiếu tiến trình tu luyện cho hồ sơ " + player, "55000");
                return new Cultivation.State(result.getInt(1), result.getLong(2), Optional.ofNullable(result.getString(3)));
            }
        }
    }

    static long xp(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT cultivation_xp FROM profiles WHERE player_id=?")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Hồ sơ chưa đăng ký: " + player);
                return result.getLong(1);
            }
        }
    }

    static Instant now(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT clock_timestamp()")) {
            result.next(); return result.getTimestamp(1).toInstant();
        }
    }

    static Optional<Cultivation.Trial> latest(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM cultivation_trials WHERE player_id=? ORDER BY deadline DESC,trial_id DESC LIMIT 1")) {
            statement.setObject(1, player);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(trial(rows)) : Optional.empty(); }
        }
    }

    private static Cultivation.Trial trial(ResultSet rows) throws SQLException {
        java.sql.Timestamp retry = rows.getTimestamp("retry_at");
        return new Cultivation.Trial(rows.getObject("trial_id", UUID.class), rows.getObject("player_id", UUID.class),
                Arrays.stream(rows.getString("sequence").split(",")).map(Integer::valueOf).toList(), rows.getInt("step"), rows.getString("status"),
                rows.getTimestamp("preview_until").toInstant(), rows.getTimestamp("deadline").toInstant(),
                Optional.ofNullable(retry).map(java.sql.Timestamp::toInstant), Optional.ofNullable(rows.getString("reason")));
    }

    private static void requireRevision(Cultivation.State state, int rank, long revision) {
        if (state.rank() != rank || state.revision() != revision)
            throw new IllegalArgumentException("Tiến trình đã thay đổi; mở lại Tu Luyện trước khi xác nhận.");
    }

    private static void consume(Connection connection, UUID request, UUID player, Map<String, Long> cost) throws SQLException {
        ResourceLedger.Account account = new ResourceLedger.Account(ResourceLedger.Type.PLAYER, player);
        for (Map.Entry<String, Long> entry : cost.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            long balance = ResourceLedger.change(connection, account, entry.getKey(), -entry.getValue());
            ResourceLedger.entry(connection, request, player, account, Optional.empty(), entry.getKey(), -entry.getValue(), balance);
        }
    }

    private static void advance(Connection connection, UUID player, int rank) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE cultivation_progress SET rank=?,revision=revision+1 WHERE player_id=?")) {
            statement.setInt(1, rank); statement.setObject(2, player);
            if (statement.executeUpdate() != 1) throw new SQLException("Không cập nhật được tiến trình " + player, "55000");
        }
    }

    static String train(Connection connection, UUID request, UUID player, int expectedRank, long revision, Cultivation.Rules rules) throws SQLException {
        Cultivation.State state = state(connection, player); requireRevision(state, expectedRank, revision);
        Cultivation.Gate gate = Cultivation.next(rules, state);
        long xp = xp(connection, player);
        if (xp < gate.xp()) throw new IllegalArgumentException("Cần " + gate.xp() + " tu vi, hiện có " + xp + "; khai thác Prison rồi trở lại động phủ.");
        consume(connection, request, player, gate.cost()); advance(connection, player, gate.rank());
        history(connection, player, Optional.of(request), Optional.empty(), "TRAIN", gate.rank(), "Tiêu hao " + gate.cost());
        return "Tu luyện thành công: Luyện Khí tầng " + gate.rank() + ". Khai thác nhận thêm " + gate.rank() * rules.bonus() + " XP/mạch.";
    }

    static String start(Connection connection, UUID request, UUID player, long revision, List<Integer> sequence, Cultivation.Rules rules) throws SQLException {
        Cultivation.State state = state(connection, player); requireRevision(state, 9, revision);
        long xp = xp(connection, player); Instant now = now(connection);
        if (xp < rules.trialXp()) throw new IllegalArgumentException("Trúc Cơ cần " + rules.trialXp() + " tu vi; hiện có " + xp);
        Optional<Cultivation.Trial> previous = latest(connection, player);
        if (previous.filter(value -> value.status().equals("ACTIVE")).isPresent()) throw new IllegalArgumentException("Đang có Trận Tâm Ma; mở lại để tiếp tục.");
        if (previous.flatMap(Cultivation.Trial::retryAt).filter(time -> now.isBefore(time)).isPresent())
            throw new IllegalArgumentException("Đang hồi phục tâm thần; thử lại sau " + previous.orElseThrow().retryAt().orElseThrow());
        if (sequence.size() != 5 || sequence.stream().anyMatch(value -> value < 0 || value > 4)) throw new IllegalArgumentException("Chuỗi thử thách phải có 5 phù ấn hợp lệ");
        consume(connection, request, player, rules.trialCost());
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO cultivation_trials(trial_id,player_id,sequence,status,preview_until,deadline) VALUES (?,?,?,'ACTIVE',?,?)")) {
            statement.setObject(1, request); statement.setObject(2, player);
            statement.setString(3, sequence.stream().map(String::valueOf).collect(Collectors.joining(",")));
            statement.setObject(4, java.sql.Timestamp.from(now.plusSeconds(rules.previewSeconds())));
            statement.setObject(5, java.sql.Timestamp.from(now.plusSeconds(rules.durationSeconds()))); statement.executeUpdate();
        }
        advance(connection, player, 9);
        history(connection, player, Optional.of(request), Optional.of(request), "START", 9, "Phí không hoàn: " + rules.trialCost());
        return "Trận Tâm Ma bắt đầu. Ghi nhớ 5 phù ấn theo thứ tự; chọn sai/hết giờ/gián đoạn mất phí. Thắng mở Kết tinh Trúc Cơ.";
    }

    static String answer(Connection connection, UUID request, UUID player, UUID trialId, int expectedStep, int choice, Cultivation.Rules rules) throws SQLException {
        if (choice < 0 || choice > 4) throw new IllegalArgumentException("Phù ấn không hợp lệ: " + choice);
        Cultivation.Trial trial;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM cultivation_trials WHERE trial_id=? AND player_id=? FOR UPDATE")) {
            statement.setObject(1, trialId); statement.setObject(2, player);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Không có thử thách thuộc người chơi: " + trialId);
                trial = trial(rows);
            }
        }
        if (!trial.status().equals("ACTIVE")) throw new IllegalArgumentException("Lượt thử đã kết thúc: " + trial.status());
        if (trial.step() != expectedStep) throw new IllegalArgumentException("Phù ấn này thuộc lượt trước; mở lại Tu Luyện.");
        Instant now = now(connection);
        if (!now.isBefore(trial.deadline())) {
            finish(connection, player, Optional.of(request), trial, "FAILED", "Hết thời gian", rules);
            return "Trận Tâm Ma thất bại: hết thời gian; phí không hoàn.";
        }
        if (now.isBefore(trial.previewUntil())) throw new IllegalArgumentException("Đang ghi nhớ; chờ phù ấn lựa chọn xuất hiện.");
        if (choice != trial.sequence().get(expectedStep)) {
            finish(connection, player, Optional.of(request), trial, "FAILED", "Chọn sai phù ấn " + (expectedStep + 1), rules);
            return "Trận Tâm Ma thất bại: chọn sai; phí không hoàn. Hồi phục " + rules.cooldownSeconds() + " giây.";
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE cultivation_trials SET step=step+1 WHERE trial_id=?")) {
            statement.setObject(1, trialId); statement.executeUpdate();
        }
        if (expectedStep == 4) {
            advance(connection, player, 10); finish(connection, player, Optional.of(request), trial, "SUCCESS", "Đủ 5 phù ấn", rules);
            return "Đột phá thành công: Trúc Cơ! Mở Kết tinh Trúc Cơ: " + rules.advanced().inputCount() + " thô → " + rules.advanced().outputCount() + " Linh Thạch.";
        }
        return "Phù ấn chính xác " + (expectedStep + 1) + "/5; tiếp tục!";
    }

    private static void finish(Connection connection, UUID player, Optional<UUID> request, Cultivation.Trial trial,
                               String status, String reason, Cultivation.Rules rules) throws SQLException {
        Instant now = now(connection);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE cultivation_trials SET status=?,finished_at=?,retry_at=?,reason=? WHERE trial_id=? AND status='ACTIVE'")) {
            statement.setString(1, status); statement.setObject(2, java.sql.Timestamp.from(now));
            statement.setObject(3, java.sql.Timestamp.from(now.plusSeconds(rules.cooldownSeconds())));
            statement.setString(4, reason); statement.setObject(5, trial.id());
            if (statement.executeUpdate() != 1) throw new SQLException("Lượt thử không còn ACTIVE: " + trial.id(), "55000");
        }
        history(connection, player, request, Optional.of(trial.id()), status, state(connection, player).rank(), reason + "; phí không hoàn");
    }

    static void expire(Connection connection, UUID player, Cultivation.Rules rules) throws SQLException {
        Optional<Cultivation.Trial> active = latest(connection, player).filter(value -> value.status().equals("ACTIVE"));
        if (active.isPresent() && !now(connection).isBefore(active.orElseThrow().deadline()))
            finish(connection, player, Optional.empty(), active.orElseThrow(), "FAILED", "Hết thời gian", rules);
    }

    static String interrupt(Connection connection, UUID player, String reason, Cultivation.Rules rules) throws SQLException {
        GameDatabase.lockPlayer(connection, player);
        Optional<Cultivation.Trial> active = latest(connection, player).filter(value -> value.status().equals("ACTIVE"));
        if (active.isEmpty()) return "Không có thử thách đang xử lý.";
        finish(connection, player, Optional.empty(), active.orElseThrow(), "INTERRUPTED", reason, rules);
        return "Thử thách gián đoạn; không thay cảnh giới, phí không hoàn, có thể thử lại sau cooldown.";
    }

    static void interruptAll(Connection connection, Cultivation.Rules rules) throws SQLException {
        List<UUID> players;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT player_id FROM cultivation_trials WHERE status='ACTIVE' ORDER BY player_id")) {
            java.util.ArrayList<UUID> values = new java.util.ArrayList<>();
            while (rows.next()) values.add(rows.getObject(1, UUID.class)); players = List.copyOf(values);
        }
        for (UUID player : players) interrupt(connection, player, "Server khởi động lại", rules);
    }

    private static void history(Connection connection, UUID player, Optional<UUID> request, Optional<UUID> trial,
                                String kind, int rank, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO cultivation_history(player_id,request_id,trial_id,kind,rank,xp,detail) VALUES (?,?,?,?,?,?,?)")) {
            statement.setObject(1, player); statement.setObject(2, request.orElse(null)); statement.setObject(3, trial.orElse(null));
            statement.setString(4, kind); statement.setInt(5, rank); statement.setLong(6, xp(connection, player)); statement.setString(7, detail); statement.executeUpdate();
        }
    }
}
