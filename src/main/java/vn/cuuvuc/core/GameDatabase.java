package vn.cuuvuc.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Cổng PostgreSQL chỉ gọi từ luồng I/O. Mỗi thao tác khóa request ID, rồi khóa hồ sơ;
 * chuyển tiền khóa hai UUID theo thứ tự cố định. Kho và ví dùng chung khóa hồ sơ.
 * Commit lưu đồng thời kết quả, kho, XP và ledger; đóng kết nối chưa commit sẽ rollback.
 * Lỗi kết nối, deadlock hoặc serialization được thử ba lần với cùng request ID.
 * Lỗi nghiệp vụ không thử lại; lỗi SQL cuối được ghi kèm ngữ cảnh rồi trả nguyên nhân gốc.
 */
public final class GameDatabase implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GameDatabase.class);
    private final HikariDataSource pool;
    private final Domain.Catalog catalog;
    private final Domain.EconomyPolicy policy;
    private final Object lifecycle = new Object();
    private final Map<UUID, String> inFlight = new ConcurrentHashMap<>();
    private boolean closing;
    private boolean closeIssued;
    public record DrainResult(boolean drained, List<String> pending) {
        public DrainResult { pending = List.copyOf(pending); }
    }

    @FunctionalInterface
    private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }

    public GameDatabase(String url, String user, String password, String schema, Domain.Catalog catalog, Domain.EconomyPolicy policy) {
        if (!schema.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Tên schema PostgreSQL không hợp lệ.");
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setSchema(schema);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "5");
        config.addDataSourceProperty("socketTimeout", "10");
        config.addDataSourceProperty("ApplicationName", "CuuVucCore");
        config.setConnectionInitSql("SET statement_timeout = '5s'");
        this.pool = new HikariDataSource(config);
        this.catalog = catalog;
        this.policy = policy;
    }

    public void initialize() throws SQLException, IOException {
        List<Migrations.Migration> migrations = Migrations.load();
        String fingerprintSql = Migrations.resource("/db/schema-fingerprint.sql");
        retry("schema", connection -> {
            Migrations.apply(connection, migrations, fingerprintSql);
            verifyPolicy(connection);
            return "schema_ready";
        });
    }

    public String register(UUID player, String name) throws SQLException {
        return retry("register:" + player, connection -> {
            ensureProfile(connection, player, name);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE profiles SET player_name=? WHERE player_id=?")) {
                statement.setString(1, name); statement.setObject(2, player); statement.executeUpdate();
            }
            return "Hồ sơ sẵn sàng. /cv profile để xem.";
        });
    }

    public String profile(UUID player) throws SQLException {
        return query("profile:" + player, connection -> {
            String description;
            try (PreparedStatement statement = connection.prepareStatement("SELECT cultivation_xp,balance,player_name FROM profiles WHERE player_id=?")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Hồ sơ chưa được đăng ký. Dùng /cv start.");
                    long xp = result.getLong(1);
                    description = result.getString(3) + " | " + Domain.stage(xp, catalog.stages()).name() + " | XP=" + xp + " | Linh tệ=" + result.getLong(2);
                }
            }
            StringBuilder inventory = new StringBuilder(description).append(" | Kho:");
            try (PreparedStatement statement = connection.prepareStatement("SELECT resource_id,quantity FROM inventory WHERE player_id=? AND quantity>0 ORDER BY resource_id")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) inventory.append(' ').append(result.getString(1)).append('=').append(result.getLong(2));
                }
            }
            return inventory.toString();
        });
    }

    public String mine(UUID request, UUID player, String name, String node, String resourceId, int cooldown) throws SQLException {
        Domain.Resource resource = Domain.resource(catalog, resourceId);
        if (resource.xp() <= 0 || cooldown < 1) throw new IllegalArgumentException("Điểm mỏ hoặc thời gian hồi không hợp lệ.");
        return operation(request, player, "mine:" + player + ":" + node + ":" + resourceId + ":" + cooldown, connection -> {
            ensureProfile(connection, player, name);
            lockPlayer(connection, player);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO mine_nodes(node_id,available_at) VALUES (?,clock_timestamp() + (? * interval '1 second')) ON CONFLICT(node_id) DO UPDATE SET available_at=EXCLUDED.available_at WHERE mine_nodes.available_at<=clock_timestamp()")) {
                statement.setString(1, node);
                statement.setInt(2, cooldown);
                if (statement.executeUpdate() != 1) return "Mạch quặng đang hồi phục.";
            }
            changeInventory(connection, player, resourceId, 1);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE profiles SET cultivation_xp=cultivation_xp+? WHERE player_id=?")) {
                statement.setLong(1, resource.xp()); statement.setObject(2, player); statement.executeUpdate();
            }
            return "+1 " + resource.name() + " | +" + resource.xp() + " XP. /cv profile để xem cảnh giới.";
        });
    }

    public String craft(UUID request, UUID player, String name, String recipeId, long count) throws SQLException {
        validateCount(count);
        Domain.Recipe recipe = Domain.recipe(catalog, recipeId);
        long input = Math.multiplyExact(recipe.inputCount(), count);
        long output = Math.multiplyExact(recipe.outputCount(), count);
        return operation(request, player, "craft:" + player + ":" + recipeId + ":" + count, connection -> {
            ensureProfile(connection, player, name); lockPlayer(connection, player);
            changeInventory(connection, player, recipe.input(), -input);
            changeInventory(connection, player, recipe.output(), output);
            return "Chế tạo thành công: " + output + " " + recipe.output();
        });
    }

    public String sell(UUID request, UUID player, String name, String resourceId, long count) throws SQLException {
        validateCount(count);
        long total = Math.multiplyExact(Domain.resource(catalog, resourceId).sell(), count);
        return operation(request, player, "sell:" + player + ":" + resourceId + ":" + count, connection -> {
            ensureProfile(connection, player, name); lockPlayer(connection, player);
            reserveMint(connection, player, total);
            changeInventory(connection, player, resourceId, -count);
            changeMoney(connection, request, player, total, "NPC_SELL:" + resourceId);
            return "Đã bán " + count + " " + resourceId + ": +" + total + " linh tệ.";
        });
    }

    public String buy(UUID request, UUID player, String name, String resourceId, long count) throws SQLException {
        validateCount(count);
        long total = Math.multiplyExact(Domain.resource(catalog, resourceId).buy(), count);
        return operation(request, player, "buy:" + player + ":" + resourceId + ":" + count, connection -> {
            ensureProfile(connection, player, name); lockPlayer(connection, player);
            changeMoney(connection, request, player, -total, "NPC_BUY:" + resourceId);
            changeInventory(connection, player, resourceId, count);
            recordBurn(connection, total);
            return "Đã mua " + count + " " + resourceId + ": -" + total + " linh tệ.";
        });
    }

    public String pay(UUID request, UUID player, String name, UUID target, long count) throws SQLException {
        validateCount(count);
        if (player.equals(target)) throw new IllegalArgumentException("Không thể chuyển tiền cho chính mình.");
        return operation(request, player, "pay:" + player + ":" + target + ":" + count, connection -> {
            ensureProfile(connection, player, name);
            UUID first = player.toString().compareTo(target.toString()) < 0 ? player : target;
            UUID second = first.equals(player) ? target : player;
            lockPlayer(connection, first); lockPlayer(connection, second);
            changeMoney(connection, request, player, -count, "TRANSFER_OUT:" + target);
            changeMoney(connection, request, target, count, "TRANSFER_IN:" + player);
            return "Đã chuyển " + count + " linh tệ tới " + target;
        });
    }

    public String history(UUID player) throws SQLException {
        return query("ledger:" + player, connection -> {
            StringBuilder history = new StringBuilder("10 giao dịch gần nhất:");
            try (PreparedStatement statement = connection.prepareStatement("SELECT delta,balance_after,reason FROM ledger WHERE player_id=? ORDER BY entry_id DESC LIMIT 10")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) history.append('\n').append(result.getLong(1)).append(" → ").append(result.getLong(2)).append(" | ").append(result.getString(3));
                }
            }
            return history.toString();
        });
    }

    public String receipt(UUID player, UUID request) throws SQLException {
        return query("receipt:" + player + ":" + request, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT result FROM operations WHERE request_id=? AND actor_id=?")) {
                statement.setObject(1, request); statement.setObject(2, player);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? "Đã commit: " + result.getString(1) : "Chưa có kết quả đã commit cho bạn; request có thể đang chạy, chưa chạy hoặc đã rollback.";
                }
            }
        });
    }

    public String economy() throws SQLException {
        return query("economy", connection -> {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT (transaction_timestamp() AT TIME ZONE 'UTC')::date, p.currency_id, p.server_daily_mint, p.player_daily_mint, coalesce(d.minted,0), coalesce(d.burned,0) FROM economy_policy p LEFT JOIN economy_daily d ON d.business_day=(transaction_timestamp() AT TIME ZONE 'UTC')::date WHERE singleton")) {
                if (!result.next()) throw new SQLException("Thiếu economy_policy.", "55000");
                return "UTC=" + result.getString(1) + " | currency=" + result.getString(2) + " | mint=" + result.getLong(5)
                        + "/" + result.getLong(3) + " | burn=" + result.getLong(6) + " | cap/player=" + result.getLong(4);
            }
        });
    }

    private String operation(UUID request, UUID player, String fingerprint, SqlWork<String> work) throws SQLException {
        return retry(fingerprint + " request=" + request, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                statement.setString(1, request.toString()); statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT fingerprint,result FROM operations WHERE request_id=?")) {
                statement.setObject(1, request);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        if (!fingerprint.equals(result.getString(1))) throw new IllegalArgumentException("Request ID đã được dùng với nội dung khác: " + request);
                        return result.getString(2);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO operations(request_id,fingerprint,result,actor_id) VALUES (?,?,'pending',?)")) {
                statement.setObject(1, request); statement.setString(2, fingerprint); statement.setObject(3, player); statement.executeUpdate();
            }
            String result = work.apply(connection) + "\nMã giao dịch: " + request;
            try (PreparedStatement statement = connection.prepareStatement("UPDATE operations SET result=? WHERE request_id=?")) {
                statement.setString(1, result); statement.setObject(2, request); statement.executeUpdate();
            }
            return result;
        });
    }

    private <T> T retry(String context, SqlWork<T> work) throws SQLException {
        UUID ticket = UUID.randomUUID();
        synchronized (lifecycle) {
            if (closing) throw new SQLException("Database đang dừng; không nhận thao tác " + context, "57P01");
            inFlight.put(ticket, context);
        }
        try { return retryOpen(context, work); }
        finally { release(ticket); }
    }

    private <T> T query(String context, SqlWork<T> work) throws SQLException {
        return retry(context, connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            return work.apply(connection);
        });
    }

    private <T> T retryOpen(String context, SqlWork<T> work) throws SQLException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection connection = pool.getConnection()) {
                connection.setAutoCommit(false);
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException failure) {
                LOG.atWarn().addKeyValue("operation", context).addKeyValue("attempt", attempt)
                        .addKeyValue("sql_state", failure.getSQLState()).setCause(failure).log("database_request_failed");
                String state = failure.getSQLState();
                if (attempt == 3 || !(failure instanceof java.sql.SQLTransientException || state != null
                        && (state.startsWith("08") || state.equals("40001") || state.equals("40P01") || state.startsWith("57P0")))) throw failure;
                try { Thread.sleep(100L * attempt); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Bị ngắt khi thử lại " + context, "57014", interrupted);
                }
            }
        }
        throw new IllegalStateException("Vòng thử lại kết thúc ngoài dự kiến.");
    }

    private static void validateCount(long count) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("Số lượng phải từ 1 đến 1000.");
    }

    private static void ensureProfile(Connection connection, UUID player, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO profiles(player_id,player_name) VALUES (?,?) ON CONFLICT(player_id) DO NOTHING")) {
            statement.setObject(1, player); statement.setString(2, name); statement.executeUpdate();
        }
    }

    private static void lockPlayer(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_id FROM profiles WHERE player_id=? FOR UPDATE")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Người nhận chưa có hồ sơ: " + player);
            }
        }
    }

    private static void changeInventory(Connection connection, UUID player, String resource, long delta) throws SQLException {
        if (delta > 0) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO inventory(player_id,resource_id,quantity) VALUES (?,?,?) ON CONFLICT(player_id,resource_id) DO UPDATE SET quantity=inventory.quantity+EXCLUDED.quantity")) {
                statement.setObject(1, player); statement.setString(2, resource); statement.setLong(3, delta); statement.executeUpdate();
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE inventory SET quantity=quantity+? WHERE player_id=? AND resource_id=? AND quantity>=?")) {
                statement.setLong(1, delta); statement.setObject(2, player); statement.setString(3, resource); statement.setLong(4, -delta);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Không đủ nguyên liệu: " + resource);
            }
        }
        if (delta < 0) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM item_tokens WHERE player_id=? AND resource_id=?")) {
                statement.setObject(1, player); statement.setString(2, resource); statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("UPDATE profiles SET inventory_revision=inventory_revision+1 WHERE player_id=?")) {
            statement.setObject(1, player); statement.executeUpdate();
        }
    }

    /** Vật phẩm là bản thể hiện quyền sở hữu đã commit; tối đa 36 token mỗi loại, phần còn lại chờ trong kho. */
    public Domain.PlayerView view(UUID player, String message) throws SQLException {
        return retry("items:" + player, connection -> {
            lockPlayer(connection, player);
            long revision; long xp; long balance;
            try (PreparedStatement statement = connection.prepareStatement("SELECT inventory_revision,cultivation_xp,balance FROM profiles WHERE player_id=?")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) {
                    result.next(); revision = result.getLong(1); xp = result.getLong(2); balance = result.getLong(3);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO item_tokens(player_id,resource_id,ordinal) SELECT i.player_id,i.resource_id,n FROM inventory i CROSS JOIN LATERAL generate_series(1,least(i.quantity,36)::integer) n WHERE i.player_id=? ON CONFLICT(player_id,resource_id,ordinal) DO NOTHING")) {
                statement.setObject(1, player); statement.executeUpdate();
            }
            Map<String, Long> stock = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT resource_id,quantity FROM inventory WHERE player_id=? AND quantity>0 ORDER BY resource_id")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) { while (result.next()) stock.put(result.getString(1), result.getLong(2)); }
            }
            List<Domain.ItemToken> items = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT token_id,resource_id FROM item_tokens WHERE player_id=? ORDER BY ordinal,resource_id")) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) items.add(new Domain.ItemToken(result.getObject(1, UUID.class), player, result.getString(2)));
                }
            }
            return new Domain.PlayerView(player, revision, xp, balance, stock, items, message);
        });
    }

    public Domain.Island reserveIsland(UUID player, String name) throws SQLException {
        return retry("island:" + player, connection -> {
            ensureProfile(connection, player, name); lockPlayer(connection, player);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO islands(player_id) SELECT ? WHERE NOT EXISTS(SELECT 1 FROM islands WHERE player_id=?)")) {
                statement.setObject(1, player); statement.setObject(2, player);
                if (statement.executeUpdate() == 1) bumpClaims(connection);
            }
            return readIslands(connection).stream().filter(island -> island.owner().equals(player)).findFirst().orElseThrow();
        });
    }

    public Domain.Claims islands() throws SQLException { return query("claims", GameDatabase::readClaims); }

    private static void bumpClaims(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE world_state SET revision=revision+1 WHERE singleton"); }
    }

    private static Domain.Claims readClaims(Connection connection) throws SQLException {
        long revision;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT revision FROM world_state WHERE singleton")) {
            if (!result.next()) throw new SQLException("Thiếu revision quyền đất.", "55000");
            revision = result.getLong(1);
        }
        return new Domain.Claims(revision, readIslands(connection));
    }

    private static List<Domain.Island> readIslands(Connection connection) throws SQLException {
        List<Domain.Island> islands = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT i.player_id,i.slot,i.ready,coalesce(array_agg(b.builder_id) FILTER (WHERE b.builder_id IS NOT NULL),'{}'::uuid[]) FROM islands i LEFT JOIN island_builders b ON b.owner_id=i.player_id GROUP BY i.player_id ORDER BY i.slot")) {
            while (result.next()) {
                java.sql.Array array = result.getArray(4);
                UUID[] builders = (UUID[]) array.getArray();
                islands.add(new Domain.Island(result.getObject(1, UUID.class), result.getInt(2), result.getBoolean(3), List.of(builders)));
                array.free();
            }
        }
        return List.copyOf(islands);
    }

    public Domain.Claims islandReady(UUID player) throws SQLException {
        return retry("island_ready:" + player, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE islands SET ready=true WHERE player_id=?")) {
                statement.setObject(1, player);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Chưa cấp động phủ cho " + player);
            }
            bumpClaims(connection);
            return readClaims(connection);
        });
    }

    public Domain.Claims inviteBuilder(UUID owner, UUID builder) throws SQLException {
        if (owner.equals(builder)) throw new IllegalArgumentException("Chủ động phủ đã có quyền xây dựng.");
        return retry("island_invite:" + owner + ":" + builder, connection -> {
            bumpClaims(connection);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO island_builders VALUES (?,?) ON CONFLICT DO NOTHING")) {
                statement.setObject(1, owner); statement.setObject(2, builder); statement.executeUpdate();
            }
            return readClaims(connection);
        });
    }

    public Domain.Claims revokeBuilder(UUID owner, UUID builder) throws SQLException {
        return retry("island_revoke:" + owner + ":" + builder, connection -> {
            bumpClaims(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM island_builders WHERE owner_id=? AND builder_id=?")) {
                statement.setObject(1, owner); statement.setObject(2, builder); statement.executeUpdate();
            }
            return readClaims(connection);
        });
    }

    /** Ký quỹ dùng cùng transaction, receipt và ledger ví; không đi qua mint/burn. */
    public String createContract(UUID contract, UUID owner, long reward, Duration lifetime) throws SQLException {
        validateCount(reward);
        if (lifetime.compareTo(Duration.ofMinutes(5)) < 0 || lifetime.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Thời hạn hợp đồng phải từ 5 phút đến 24 giờ.");
        UUID request = Contracts.request(contract, "CREATE");
        return operation(request, owner, "contract:create:" + contract + ":" + owner + ":" + reward + ":" + lifetime, connection -> {
            lockPlayer(connection, owner);
            int slot;
            try (PreparedStatement statement = connection.prepareStatement("SELECT slot FROM islands WHERE player_id=? AND ready")) {
                statement.setObject(1, owner);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Cần động phủ sẵn sàng trước khi tạo hợp đồng.");
                    slot = result.getInt(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM building_contracts WHERE island_slot=? AND status IN ('OPEN','ACTIVE','SUBMITTED')")) {
                statement.setInt(1, slot);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) throw new IllegalArgumentException("Động phủ đã có hợp đồng chưa kết thúc.");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO building_contracts(contract_id,owner_id,island_slot,reward,status,expires_at) VALUES (?,?,?,?,'OPEN',clock_timestamp()+(? * interval '1 millisecond'))")) {
                statement.setObject(1, contract); statement.setObject(2, owner); statement.setInt(3, slot);
                statement.setLong(4, reward); statement.setLong(5, lifetime.toMillis()); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO contract_escrow_accounts VALUES (?,'linh_te',0)")) {
                statement.setObject(1, contract); statement.executeUpdate();
            }
            changeMoney(connection, request, owner, -reward, "CONTRACT_DEPOSIT:" + contract);
            escrowEntry(connection, contract, request, "DEPOSIT", reward);
            contractEvent(connection, contract, owner, "CREATE", "Ký quỹ " + reward + " Linh Tệ; phí 0%; vật liệu ngoài ký quỹ.");
            return "Đã ký quỹ " + reward + " Linh Tệ. Hợp đồng: " + contract;
        });
    }

    public String acceptContract(UUID contract, UUID builder) throws SQLException {
        return operation(Contracts.request(contract, "ACCEPT"), builder, "contract:accept:" + contract + ":" + builder, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            requireContractStatus(connection, job, Contracts.Status.OPEN);
            if (job.owner().equals(builder)) throw new IllegalArgumentException("Chủ hợp đồng không thể tự nhận việc.");
            lockPlayer(connection, builder);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE building_contracts SET builder_id=? WHERE contract_id=?")) {
                statement.setObject(1, builder); statement.setObject(2, contract); statement.executeUpdate();
            }
            contractStatus(connection, contract, Contracts.Status.ACTIVE);
            contractEvent(connection, contract, builder, "ACCEPT", "Chưa chuyển tiền công; quyền thi công theo hợp đồng.");
            return "Đã nhận hợp đồng " + contract + ". Tiền công vẫn ở ký quỹ.";
        });
    }

    /** Connector thế giới phải kiểm tra công trình và lưu world trước khi gửi nghiệm thu. */
    public String submitContract(UUID contract, UUID builder) throws SQLException {
        return operation(Contracts.request(contract, "SUBMIT"), builder, "contract:submit:" + contract + ":" + builder, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            requireContractStatus(connection, job, Contracts.Status.ACTIVE);
            requireBuilder(job, builder);
            contractStatus(connection, contract, Contracts.Status.SUBMITTED);
            contractEvent(connection, contract, builder, "SUBMIT", "Gửi chủ hợp đồng nghiệm thu; quyền thi công đã kết thúc.");
            return "Đã gửi nghiệm thu. Chờ chủ hợp đồng duyệt trước thời hạn.";
        });
    }

    /** Chỉ chủ hợp đồng duyệt; connector thế giới kiểm tra lại và lưu world trước khi thanh toán. */
    public String completeContract(UUID contract, UUID owner) throws SQLException {
        UUID request = Contracts.request(contract, "COMPLETE");
        return operation(request, owner, "contract:complete:" + contract + ":" + owner, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            requireOwner(job, owner);
            requireContractStatus(connection, job, Contracts.Status.SUBMITTED);
            UUID builder = job.builder().orElseThrow();
            lockPlayer(connection, builder);
            settleEscrow(connection, job, request, "RELEASE");
            changeMoney(connection, request, builder, job.reward(), "CONTRACT_PAYMENT:" + contract);
            contractStatus(connection, contract, Contracts.Status.COMPLETED);
            contractEvent(connection, contract, owner, "COMPLETE", "Nghiệm thu; trả " + job.reward() + " Linh Tệ cho " + builder);
            return "Đã nghiệm thu và thanh toán " + job.reward() + " Linh Tệ; phí 0%.";
        });
    }

    public String cancelContract(UUID contract, UUID owner) throws SQLException {
        UUID request = Contracts.request(contract, "CANCEL");
        return operation(request, owner, "contract:cancel:" + contract + ":" + owner, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            requireOwner(job, owner);
            requireContractStatus(connection, job, Contracts.Status.OPEN);
            refundContract(connection, job, request, owner, Contracts.Status.CANCELLED, "CANCEL");
            return "Đã hủy hợp đồng chưa nhận; hoàn " + job.reward() + " Linh Tệ.";
        });
    }

    public String abandonContract(UUID contract, UUID builder) throws SQLException {
        UUID request = Contracts.request(contract, "ABANDON");
        return operation(request, builder, "contract:abandon:" + contract + ":" + builder, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            requireBuilder(job, builder);
            if (job.status() != Contracts.Status.ACTIVE && job.status() != Contracts.Status.SUBMITTED)
                throw new IllegalArgumentException("Chỉ bỏ việc đang thi công hoặc chờ nghiệm thu.");
            refundContract(connection, job, request, builder, Contracts.Status.CANCELLED, "CANCEL");
            return "Đã bỏ việc; hoàn Linh Tệ cho bên thuê. Công trình và vật liệu không tự hoàn trả.";
        });
    }

    /** Worker chỉ chọn một lô nhỏ; khóa hợp đồng quyết định duy nhất hoàn tiền hay thanh toán. */
    public void expireContracts() throws SQLException {
        Contracts.Board board = contractBoard();
        List<Contracts.Job> candidates = board.jobs().stream().filter(Contracts::unresolved)
                .filter(job -> !job.expiresAt().isAfter(board.databaseTime())).limit(32).toList();
        for (Contracts.Job candidate : candidates) {
            UUID contract = candidate.id(); UUID owner = candidate.owner(); UUID request = Contracts.request(contract, "EXPIRE");
            operation(request, owner, "contract:expire:" + contract, connection -> {
                Contracts.Job job = lockContract(connection, contract);
                if (!Contracts.unresolved(job)) return "Hợp đồng đã kết thúc; không hoàn thêm.";
                if (databaseTime(connection).isBefore(job.expiresAt())) throw new SQLException("Đồng hồ DB đổi trước khi hết hạn: " + contract, "40001");
                refundContract(connection, job, request, owner, Contracts.Status.EXPIRED, "EXPIRE");
                return "Hợp đồng hết hạn; hoàn " + job.reward() + " Linh Tệ.";
            });
        }
    }

    public Contracts.Board contractBoard() throws SQLException {
        return query("contract_board", connection -> {
            long revision;
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT revision FROM contract_state WHERE singleton")) {
                if (!result.next()) throw new SQLException("Thiếu contract_state.", "55000");
                revision = result.getLong(1);
            }
            List<Contracts.Job> jobs = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(contractSelect() + " ORDER BY c.created_at,c.contract_id")) {
                while (result.next()) jobs.add(readContract(result));
            }
            return new Contracts.Board(revision, databaseTime(connection), jobs);
        });
    }

    public String contractHistory(UUID contract) throws SQLException {
        return query("contract_history:" + contract, connection -> {
            StringBuilder text = new StringBuilder("Hợp đồng " + contract);
            try (PreparedStatement statement = connection.prepareStatement("SELECT created_at,event_type,actor_id,detail FROM building_history WHERE contract_id=? ORDER BY event_id DESC LIMIT 20")) {
                statement.setObject(1, contract);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) text.append('\n').append(result.getString(1)).append(" | ").append(result.getString(2))
                            .append(" | ").append(result.getString(3)).append(" | ").append(result.getString(4));
                }
            }
            return text.toString();
        });
    }

    /** Ghi đúng thứ tự sự kiện Paper đã được phép; không suy diễn từ thao tác bị hủy. */
    public String contractBlockChanged(UUID request, UUID contract, UUID actor, String event, String position) throws SQLException {
        if (!event.equals("PLACE") && !event.equals("BREAK")) throw new IllegalArgumentException("Sự kiện block không hợp lệ: " + event);
        return operation(request, actor, "contract:block:" + contract + ":" + actor + ":" + event + ":" + position, connection -> {
            Contracts.Job job = lockContract(connection, contract);
            if (job.builder().filter(actor::equals).isEmpty()) throw new IllegalArgumentException("Sự kiện không thuộc Builder: " + contract);
            contractEvent(connection, contract, actor, event, position);
            return "Đã ghi lịch sử xây dựng.";
        });
    }

    private static String contractSelect() {
        return "SELECT c.contract_id,c.owner_id,c.builder_id,c.island_slot,c.reward,e.balance,c.status,c.expires_at,c.revision FROM building_contracts c JOIN contract_escrow_accounts e USING(contract_id)";
    }

    private static Contracts.Job readContract(ResultSet result) throws SQLException {
        return new Contracts.Job(result.getObject(1, UUID.class), result.getObject(2, UUID.class), Optional.ofNullable(result.getObject(3, UUID.class)),
                result.getInt(4), result.getLong(5), result.getLong(6), Contracts.Status.valueOf(result.getString(7)), result.getTimestamp(8).toInstant(), result.getLong(9));
    }

    private static Contracts.Job lockContract(Connection connection, UUID contract) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(contractSelect() + " WHERE c.contract_id=? FOR UPDATE OF c,e")) {
            statement.setObject(1, contract);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không có hợp đồng: " + contract);
                return readContract(result);
            }
        }
    }

    private static Instant databaseTime(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT clock_timestamp()")) {
            result.next(); return result.getTimestamp(1).toInstant();
        }
    }

    private static void requireContractStatus(Connection connection, Contracts.Job job, Contracts.Status status) throws SQLException {
        if (job.status() != status) throw new IllegalArgumentException("Hợp đồng cần trạng thái " + status + "; hiện tại " + job.status());
        if (!databaseTime(connection).isBefore(job.expiresAt())) throw new IllegalArgumentException("Hợp đồng đã hết hạn; chờ hoàn ký quỹ.");
    }

    private static void requireOwner(Contracts.Job job, UUID player) {
        if (!job.owner().equals(player)) throw new IllegalArgumentException("Chỉ chủ hợp đồng được thực hiện thao tác này.");
    }

    private static void requireBuilder(Contracts.Job job, UUID player) {
        if (job.builder().filter(player::equals).isEmpty()) throw new IllegalArgumentException("Chỉ Builder của hợp đồng được thực hiện thao tác này.");
    }

    private static void escrowEntry(Connection connection, UUID contract, UUID request, String kind, long delta) throws SQLException {
        long balance;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE contract_escrow_accounts SET balance=balance+? WHERE contract_id=? AND balance+? BETWEEN 0 AND 1000 RETURNING balance")) {
            statement.setLong(1, delta); statement.setObject(2, contract); statement.setLong(3, delta);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Ký quỹ không đủ hoặc không hợp lệ: " + contract, "55000");
                balance = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO contract_escrow_ledger(contract_id,request_id,kind,delta,balance_after) VALUES (?,?,?,?,?)")) {
            statement.setObject(1, contract); statement.setObject(2, request); statement.setString(3, kind);
            statement.setLong(4, delta); statement.setLong(5, balance); statement.executeUpdate();
        }
    }

    private static void settleEscrow(Connection connection, Contracts.Job job, UUID request, String kind) throws SQLException {
        if (job.escrow() != job.reward()) throw new SQLException("Ký quỹ lệch tiền công: " + job.id(), "55000");
        escrowEntry(connection, job.id(), request, kind, -job.reward());
    }

    private static void refundContract(Connection connection, Contracts.Job job, UUID request, UUID actor, Contracts.Status status, String event) throws SQLException {
        lockPlayer(connection, job.owner());
        settleEscrow(connection, job, request, "REFUND");
        changeMoney(connection, request, job.owner(), job.reward(), "CONTRACT_REFUND:" + job.id());
        contractStatus(connection, job.id(), status);
        contractEvent(connection, job.id(), actor, event, "Hoàn " + job.reward() + " Linh Tệ; phí 0%; công trình không tự tháo dỡ.");
    }

    private static void contractStatus(Connection connection, UUID contract, Contracts.Status status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE building_contracts SET status=?,revision=revision+1 WHERE contract_id=?")) {
            statement.setString(1, status.name()); statement.setObject(2, contract); statement.executeUpdate();
        }
    }

    private static void contractEvent(Connection connection, UUID contract, UUID actor, String event, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO building_history(contract_id,actor_id,event_type,detail) VALUES (?,?,?,?)")) {
            statement.setObject(1, contract); statement.setObject(2, actor); statement.setString(3, event); statement.setString(4, detail); statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE contract_state SET revision=revision+1 WHERE singleton"); }
    }

    private static void changeMoney(Connection connection, UUID request, UUID player, long delta, String reason) throws SQLException {
        long balance;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE profiles SET balance=balance+? WHERE player_id=? AND (? > 0 OR balance>=?) RETURNING balance")) {
            statement.setLong(1, delta); statement.setObject(2, player); statement.setLong(3, delta); statement.setLong(4, -delta);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không đủ linh tệ.");
                balance = result.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger(request_id,player_id,delta,balance_after,reason) VALUES (?,?,?,?,?)")) {
            statement.setObject(1, request); statement.setObject(2, player); statement.setLong(3, delta);
            statement.setLong(4, balance); statement.setString(5, reason); statement.executeUpdate();
        }
    }

    private void verifyPolicy(Connection connection) throws SQLException {
        String signature = CatalogFile.signature(catalog);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO economy_policy VALUES (true,'linh_te',?,?,?) ON CONFLICT DO NOTHING")) {
            statement.setLong(1, policy.serverDailyMint()); statement.setLong(2, policy.playerDailyMint()); statement.setString(3, signature); statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT server_daily_mint,player_daily_mint,catalog_sha256 FROM economy_policy WHERE singleton")) {
            result.next();
            if (result.getLong(1) != policy.serverDailyMint() || result.getLong(2) != policy.playerDailyMint() || !signature.equals(result.getString(3)))
                throw new SQLException("Catalog hoặc mint policy không khớp DB. Dừng các server và triển khai cập nhật chính sách có kiểm soát.", "55000");
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT DISTINCT resource_id FROM inventory")) {
            while (result.next()) if (!catalog.resources().containsKey(result.getString(1)))
                throw new SQLException("Kho chứa resource không có trong catalog: " + result.getString(1), "55000");
        }
    }

    private static void reserveMint(Connection connection, UUID player, long total) throws SQLException {
        long globalCap;
        long playerCap;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT server_daily_mint,player_daily_mint FROM economy_policy WHERE singleton FOR SHARE")) {
            if (!result.next()) throw new SQLException("Thiếu economy_policy.", "55000");
            globalCap = result.getLong(1); playerCap = result.getLong(2);
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO economy_daily VALUES ((transaction_timestamp() AT TIME ZONE 'UTC')::date,?,0) ON CONFLICT(business_day) DO UPDATE SET minted=economy_daily.minted+EXCLUDED.minted WHERE economy_daily.minted<=?")) {
            if (total > globalCap) throw new IllegalArgumentException("Vượt hạn mức phát hành NPC toàn server hôm nay (UTC).");
            statement.setLong(1, total); statement.setLong(2, globalCap - total);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Đã hết hạn mức phát hành NPC toàn server hôm nay (UTC).");
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_mint_daily VALUES ((transaction_timestamp() AT TIME ZONE 'UTC')::date,?,?) ON CONFLICT(business_day,player_id) DO UPDATE SET minted=player_mint_daily.minted+EXCLUDED.minted WHERE player_mint_daily.minted<=?")) {
            if (total > playerCap) throw new IllegalArgumentException("Vượt hạn mức bán NPC của bạn hôm nay (UTC).");
            statement.setObject(1, player); statement.setLong(2, total); statement.setLong(3, playerCap - total);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Bạn đã hết hạn mức bán NPC hôm nay (UTC).");
        }
    }

    private static void recordBurn(Connection connection, long total) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO economy_daily VALUES ((transaction_timestamp() AT TIME ZONE 'UTC')::date,0,?) ON CONFLICT(business_day) DO UPDATE SET burned=economy_daily.burned+EXCLUDED.burned")) {
            statement.setLong(1, total); statement.executeUpdate();
        }
    }

    private void release(UUID ticket) {
        boolean closePool;
        synchronized (lifecycle) {
            inFlight.remove(ticket);
            closePool = closing && inFlight.isEmpty() && !closeIssued;
            if (closePool) closeIssued = true;
            lifecycle.notifyAll();
        }
        if (closePool) pool.close();
    }

    /**
     * Hàng rào shutdown ngừng nhận I/O trước khi chờ. Hết thời gian chờ vẫn giữ pool
     * cho transaction đã bắt đầu; tác vụ cuối tự đóng pool. Pending được log bền trong
     * báo cáo shutdown của plugin để tra receipt sau restart; không tự phát lại request chưa rõ kết quả.
     */
    public DrainResult drainAndClose(Duration timeout) {
        if (timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException("Drain timeout phải từ 0 đến 30 giây.");
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean closePool;
        List<String> pending;
        synchronized (lifecycle) {
            closing = true;
            while (!inFlight.isEmpty() && System.nanoTime() < deadline) {
                try { TimeUnit.NANOSECONDS.timedWait(lifecycle, Math.max(1, deadline - System.nanoTime())); }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    LOG.atWarn().setCause(failure).log("database_drain_interrupted");
                    break;
                }
            }
            pending = List.copyOf(inFlight.values());
            closePool = pending.isEmpty() && !closeIssued;
            if (closePool) closeIssued = true;
        }
        if (closePool) pool.close();
        if (pending.isEmpty()) LOG.info("database_drain_complete");
        else LOG.atWarn().addKeyValue("pending", pending).addKeyValue("timeout_ms", timeout.toMillis()).log("database_drain_timeout_deferred_close");
        return new DrainResult(pending.isEmpty(), pending);
    }

    @Override public void close() { drainAndClose(Duration.ofSeconds(15)); }
}
