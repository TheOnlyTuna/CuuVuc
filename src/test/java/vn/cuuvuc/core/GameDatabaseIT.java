package vn.cuuvuc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class GameDatabaseIT {
    private String url;
    private String user;
    private String password;
    private String schema;
    private Domain.Catalog catalog;
    private GameDatabase database;

    @BeforeEach void start() throws SQLException, IOException {
        url = required("CUUVUC_TEST_URL"); user = required("CUUVUC_TEST_USER"); password = required("CUUVUC_TEST_PASSWORD");
        schema = "cv_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        catalog = CatalogFile.read(getClass().getResourceAsStream("/catalog.properties"));
        database = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500));
        database.initialize();
    }

    @AfterEach void stop() throws SQLException {
        if (database != null) database.close();
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Integration tests require " + key + "; use the dedicated test PostgreSQL database.");
        return value;
    }

    private long scalar(String sql, UUID player) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, player);
                try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); }
            }
        }
    }

    private long balance(UUID player) throws SQLException { return scalar("SELECT balance FROM profiles WHERE player_id=?", player); }
    private long stock(UUID player) throws SQLException { return scalar("SELECT coalesce(sum(quantity),0) FROM inventory WHERE player_id=?", player); }
    private void mine(UUID player, int times) throws SQLException {
        for (int index = 0; index < times; index++) database.mine(UUID.randomUUID(), player, "Cultivator", UUID.randomUUID().toString(), "linh_thach_tho", 3);
    }
    private void fund(UUID player, int times) throws SQLException {
        mine(player, times); database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", times);
    }

    @Test void fullGameplayLoopAndLedgerReconcile() throws SQLException {
        UUID player = UUID.randomUUID(); UUID target = UUID.randomUUID();
        database.register(target, "Friend");
        mine(player, 10);
        database.craft(UUID.randomUUID(), player, "Cultivator", "ket_tinh", 2);
        database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach", 2);
        database.buy(UUID.randomUUID(), player, "Cultivator", "huyen_thiet", 1);
        database.pay(UUID.randomUUID(), player, "Cultivator", target, 6);
        assertEquals(20, balance(player)); assertEquals(6, balance(target)); assertEquals(1, stock(player));
        assertTrue(database.profile(player).contains("Luyện Khí"));
        assertEquals(balance(player), scalar("SELECT sum(delta) FROM ledger WHERE player_id=?", player));
        assertEquals(balance(target), scalar("SELECT sum(delta) FROM ledger WHERE player_id=?", target));
    }

    @Test void reconnectRetainsXpInventoryAndMoney() throws SQLException {
        UUID player = UUID.randomUUID(); mine(player, 3);
        database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1);
        try (GameDatabase reopened = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500))) {
            String profile = reopened.profile(player);
            assertTrue(profile.contains("XP=30")); assertTrue(profile.contains("Linh tệ=2")); assertTrue(profile.contains("linh_thach_tho=2"));
        }
    }

    @Test void insufficientCraftRollsBackEntireOperation() throws SQLException {
        UUID player = UUID.randomUUID(); mine(player, 4); UUID request = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> database.craft(request, player, "Cultivator", "ket_tinh", 1));
        assertEquals(4, stock(player));
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request));
    }

    @Test void insufficientMoneyAndMaterialsDoNotCreateLedgerEntries() throws SQLException {
        UUID player = UUID.randomUUID(); database.register(player, "Cultivator");
        assertThrows(IllegalArgumentException.class, () -> database.buy(UUID.randomUUID(), player, "Cultivator", "huyen_thiet", 1));
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "huyen_thiet", 1));
        assertEquals(0, balance(player)); assertEquals(0, stock(player));
        assertEquals(0, scalar("SELECT count(*) FROM ledger WHERE player_id=?", player));
    }

    @Test void sameRequestConcurrentReplayCreditsOnce() throws Exception {
        UUID player = UUID.randomUUID(); mine(player, 1); UUID request = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> actions = IntStream.range(0, 8).mapToObj(index -> (Callable<String>) () -> database.sell(request, player, "Cultivator", "linh_thach_tho", 1)).toList();
            List<Future<String>> results = executor.invokeAll(actions);
            for (Future<String> result : results) assertTrue(result.get().contains("+2"));
        }
        assertEquals(2, balance(player)); assertEquals(0, stock(player));
        assertEquals(1, scalar("SELECT count(*) FROM ledger WHERE player_id=?", player));
        assertThrows(IllegalArgumentException.class, () -> database.sell(request, player, "Cultivator", "linh_thach_tho", 2));
    }

    @Test void competingSpendsCannotOverdraw() throws Exception {
        UUID player = UUID.randomUUID(); fund(player, 5);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> actions = IntStream.range(0, 8).mapToObj(index -> (Callable<Boolean>) () -> {
                try { database.buy(UUID.randomUUID(), player, "Cultivator", "huyen_thiet", 1); return true; }
                catch (IllegalArgumentException expected) { assertEquals("Không đủ linh tệ.", expected.getMessage()); return false; }
            }).toList();
            long successes = 0;
            for (Future<Boolean> result : executor.invokeAll(actions)) if (result.get()) successes++;
            assertEquals(1, successes);
        }
        assertEquals(0, balance(player)); assertEquals(1, stock(player));
    }

    @Test void oneNodeCanRewardOnlyOneConcurrentMiner() throws Exception {
        UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID(); String node = UUID.randomUUID().toString();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = executor.invokeAll(List.of(
                    () -> database.mine(UUID.randomUUID(), first, "First", node, "linh_thach_tho", 3600),
                    () -> database.mine(UUID.randomUUID(), second, "Second", node, "linh_thach_tho", 3600)));
            for (Future<String> result : results) result.get();
        }
        assertEquals(1, stock(first) + stock(second));
    }

    @Test void cooldownSurvivesConnectorRestart() throws SQLException {
        UUID player = UUID.randomUUID(); String node = UUID.randomUUID().toString();
        database.mine(UUID.randomUUID(), player, "Cultivator", node, "linh_thach_tho", 3600);
        try (GameDatabase reopened = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500))) {
            assertTrue(reopened.mine(UUID.randomUUID(), player, "Cultivator", node, "linh_thach_tho", 3600).startsWith("Mạch quặng đang hồi phục."));
        }
        assertEquals(1, stock(player));
    }

    @Test void reciprocalTransfersConserveCurrency() throws Exception {
        UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID(); fund(first, 20); fund(second, 20);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> actions = IntStream.range(0, 20).mapToObj(index -> (Callable<String>) () -> index % 2 == 0
                    ? database.pay(UUID.randomUUID(), first, "First", second, 1)
                    : database.pay(UUID.randomUUID(), second, "Second", first, 1)).toList();
            for (Future<String> result : executor.invokeAll(actions)) result.get();
        }
        assertEquals(40, balance(first)); assertEquals(40, balance(second));
        assertEquals(40, scalar("SELECT sum(delta) FROM ledger WHERE player_id=?", first));
    }

    @Test void ledgerRejectsEdits() throws SQLException {
        UUID player = UUID.randomUUID(); fund(player, 1);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM ledger WHERE player_id=?")) {
                statement.setObject(1, player);
                SQLException failure = assertThrows(SQLException.class, statement::executeUpdate);
                assertEquals("55000", failure.getSQLState());
            }
        }
        assertEquals(1, scalar("SELECT count(*) FROM ledger WHERE player_id=?", player));
    }

    @Test void rejectedTransfersAndInvalidAmountsLeaveBalanceUntouched() throws SQLException {
        UUID player = UUID.randomUUID(); fund(player, 2);
        assertThrows(IllegalArgumentException.class, () -> database.pay(UUID.randomUUID(), player, "Cultivator", player, 1));
        assertThrows(IllegalArgumentException.class, () -> database.pay(UUID.randomUUID(), player, "Cultivator", UUID.randomUUID(), 1));
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", -1));
        assertEquals(4, balance(player));
    }

    @Test void migrationIsRepeatable() throws SQLException, IOException { database.initialize(); database.initialize(); }

    @Test void databaseFailureAfterInventoryDebitRollsBack() throws SQLException {
        UUID player = UUID.randomUUID(); mine(player, 1);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE profiles SET balance=? WHERE player_id=?")) {
                statement.setLong(1, Long.MAX_VALUE); statement.setObject(2, player); statement.executeUpdate();
            }
        }
        UUID request = UUID.randomUUID();
        SQLException failure = assertThrows(SQLException.class, () -> database.sell(request, player, "Cultivator", "linh_thach_tho", 1));
        assertEquals("22003", failure.getSQLState());
        assertEquals(1, stock(player)); assertEquals(Long.MAX_VALUE, balance(player));
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
        }
    }

    private void stockFixture(UUID player, long count) throws SQLException {
        database.register(player, "Cultivator");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO inventory VALUES (?,'linh_thach_tho',?)")) {
                statement.setObject(1, player); statement.setLong(2, count); statement.executeUpdate();
            }
        }
    }

    @Test void readQueriesNeverAppendOperationLog() throws SQLException {
        UUID player = UUID.randomUUID(); database.register(player, "Reader");
        for (int index = 0; index < 20; index++) { database.profile(player); database.history(player); database.receipt(player, UUID.randomUUID()); database.economy(); }
        assertEquals(0, number("SELECT count(*) FROM operations"));
        assertEquals(0, number("SELECT count(*) FROM ledger"));
    }

    @Test void personalCapCannotBeRefilledByBurnOrTransfer() throws SQLException {
        execute("UPDATE economy_policy SET player_daily_mint=6");
        UUID player = UUID.randomUUID(); UUID friend = UUID.randomUUID(); stockFixture(player, 10); database.register(friend, "Friend");
        database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 3);
        database.buy(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1);
        database.pay(UUID.randomUUID(), player, "Cultivator", friend, 1);
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1));
        assertEquals(1, balance(player)); assertEquals(8, stock(player));
        assertEquals(6, number("SELECT sum(minted) FROM economy_daily"));
        assertEquals(4, number("SELECT sum(burned) FROM economy_daily"));
        assertEquals(6, number("SELECT sum(minted) FROM player_mint_daily"));
    }

    @Test void serverCapSerializesDifferentPlayersAcrossConnections() throws Exception {
        execute("UPDATE economy_policy SET server_daily_mint=10,player_daily_mint=6");
        List<UUID> players = IntStream.range(0, 8).mapToObj(index -> UUID.randomUUID()).toList();
        for (UUID player : players) stockFixture(player, 1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> actions = players.stream().map(player -> (Callable<Boolean>) () -> {
                try { database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1); return true; }
                catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("toàn server")); return false; }
            }).toList();
            long successes = 0;
            for (Future<Boolean> result : executor.invokeAll(actions)) if (result.get()) successes++;
            assertEquals(5, successes);
        }
        assertEquals(10, number("SELECT sum(balance) FROM profiles"));
        assertEquals(10, number("SELECT sum(minted) FROM economy_daily"));
        assertEquals(3, number("SELECT sum(quantity) FROM inventory"));
    }

    @Test void failedSaleAndReplayDoNotConsumeExtraQuota() throws SQLException {
        UUID player = UUID.randomUUID(); stockFixture(player, 1);
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 2));
        assertEquals(0, number("SELECT count(*) FROM economy_daily"));
        UUID request = UUID.randomUUID(); database.sell(request, player, "Cultivator", "linh_thach_tho", 1);
        database.sell(request, player, "Cultivator", "linh_thach_tho", 1);
        assertEquals(2, number("SELECT sum(minted) FROM economy_daily"));
        assertEquals(2, number("SELECT sum(minted) FROM player_mint_daily"));
        assertTrue(database.receipt(player, request).startsWith("Đã commit:"));
        assertTrue(database.receipt(UUID.randomUUID(), request).startsWith("Chưa có"));
    }

    @Test void previousUtcDayDoesNotSpendTodaysQuota() throws SQLException {
        execute("INSERT INTO economy_daily VALUES ((transaction_timestamp() AT TIME ZONE 'UTC')::date-1,10000,0)");
        UUID player = UUID.randomUUID(); stockFixture(player, 1);
        database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1);
        assertEquals(2, number("SELECT minted FROM economy_daily WHERE business_day=(transaction_timestamp() AT TIME ZONE 'UTC')::date"));
    }

    @Test void driftAndChecksumMismatchFailClosed() throws SQLException {
        execute("ALTER TABLE profiles ADD COLUMN unexpected text");
        SQLException drift = assertThrows(SQLException.class, database::initialize);
        assertEquals("55000", drift.getSQLState()); assertTrue(drift.getMessage().contains("drift"));
        execute("ALTER TABLE profiles DROP COLUMN unexpected");
        execute("UPDATE migration_history SET script_sha256='tampered' WHERE version=1");
        assertEquals("55000", assertThrows(SQLException.class, database::initialize).getSQLState());
    }

    @Test void futureSchemaAndCatalogMismatchFailClosed() throws SQLException {
        execute("UPDATE schema_version SET version=99");
        assertEquals("55000", assertThrows(SQLException.class, database::initialize).getSQLState());
        execute("UPDATE schema_version SET version=5");
        execute("UPDATE economy_policy SET catalog_sha256='different-catalog'");
        assertEquals("55000", assertThrows(SQLException.class, database::initialize).getSQLState());
    }

    private void legacySchema() throws SQLException, IOException {
        database.close();
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            statement.execute(Migrations.resource("/db/migrations/V001__baseline.sql"));
        }
        database = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500));
    }

    @Test void legacyUpgradePreservesBalancesAndCountsExistingMint() throws SQLException, IOException {
        legacySchema();
        UUID player = UUID.randomUUID(); UUID request = UUID.randomUUID();
        execute("INSERT INTO profiles(player_id,player_name,balance,cultivation_xp) VALUES ('" + player + "','Legacy',600,80)");
        execute("INSERT INTO inventory VALUES ('" + player + "','linh_thach_tho',3)");
        execute("INSERT INTO operations(request_id,fingerprint,result) VALUES ('" + request + "','sell:" + player + ":linh_thach_tho:300','legacy_success')");
        execute("INSERT INTO ledger(request_id,player_id,delta,balance_after,reason) VALUES ('" + request + "','" + player + "',600,600,'NPC_SELL:linh_thach_tho')");
        database.initialize(); database.initialize();
        assertEquals(600, balance(player)); assertEquals(3, stock(player));
        assertEquals(5, number("SELECT version FROM schema_version"));
        assertEquals(5, number("SELECT count(*) FROM migration_history"));
        assertEquals(600, number("SELECT sum(minted) FROM player_mint_daily"));
        assertTrue(database.receipt(player, request).contains("legacy_success"));
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Legacy", "linh_thach_tho", 1));
        assertEquals(3, stock(player));
    }

    @Test void physicalTokensSurviveReconnectAndConsumptionInvalidatesCopies() throws SQLException {
        UUID player = UUID.randomUUID(); mine(player, 5);
        Domain.PlayerView before = database.view(player, "before");
        assertEquals(5, before.items().size());
        assertEquals(before.items(), database.view(player, "reconnect").items());
        database.craft(UUID.randomUUID(), player, "Cultivator", "ket_tinh", 1);
        Domain.PlayerView after = database.view(player, "after");
        assertEquals(1, after.items().size()); assertEquals("linh_thach", after.items().getFirst().resource());
        assertTrue(after.revision() > before.revision());
        assertTrue(before.items().stream().noneMatch(after.items()::contains));
        database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach", 1);
        assertTrue(database.view(player, "sold").items().isEmpty());
        assertEquals(18, balance(player));
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach", 1));
    }

    @Test void tokenProjectionIsBoundedAndDoesNotSpendMailboxStock() throws SQLException {
        UUID player = UUID.randomUUID(); stockFixture(player, 1_000_000_000L);
        Domain.PlayerView view = database.view(player, "full inventory");
        assertEquals(36, view.items().size()); assertEquals(1_000_000_000L, stock(player));
        assertEquals(0, number("SELECT count(*) FROM operations"));
        assertEquals(view.items(), database.view(player, "retry").items());
        assertThrows(IllegalArgumentException.class, () -> database.view(UUID.randomUUID(), "foreign"));
    }

    @Test void failedSaleKeepsPhysicalTokensAndRevision() throws SQLException {
        UUID player = UUID.randomUUID(); stockFixture(player, 2);
        Domain.PlayerView before = database.view(player, "before");
        assertThrows(IllegalArgumentException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 3));
        Domain.PlayerView after = database.view(player, "after");
        assertEquals(before.items(), after.items()); assertEquals(before.revision(), after.revision());
    }

    @Test void concurrentIslandRequestsReserveOneStableSlot() throws Exception {
        UUID player = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Domain.Island>> requests = IntStream.range(0, 8).mapToObj(index -> (Callable<Domain.Island>) () -> database.reserveIsland(player, "Cultivator")).toList();
            for (Future<Domain.Island> result : executor.invokeAll(requests)) assertEquals(1, result.get().slot());
        } finally { executor.shutdownNow(); }
        assertEquals(1, database.islands().islands().size());
        assertEquals(2, database.reserveIsland(UUID.randomUUID(), "Friend").slot());
        assertFalse(database.reserveIsland(player, "Cultivator").ready());
        database.islandReady(player);
        assertTrue(database.reserveIsland(player, "Cultivator").ready());
    }

    @Test void builderRightsAreBoundedPersistedAndRevocable() throws SQLException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID stranger = UUID.randomUUID();
        database.register(builder, "Friend"); database.reserveIsland(owner, "Cultivator");
        Domain.Claims before = database.islandReady(owner);
        Domain.Island island = before.islands().getFirst();
        assertTrue(Domain.canBuild(island, owner)); assertFalse(Domain.canBuild(island, builder));
        Domain.Claims invited = database.inviteBuilder(owner, builder);
        assertTrue(Domain.canBuild(invited.islands().getFirst(), builder));
        assertFalse(Domain.canBuild(invited.islands().getFirst(), stranger));
        assertTrue(invited.revision() > before.revision());
        assertTrue(Domain.islandAt(invited.islands(), 64, 0).isPresent());
        assertTrue(Domain.islandAt(invited.islands(), 79, 15).isPresent());
        assertTrue(Domain.islandAt(invited.islands(), 80, 15).isEmpty());
        Domain.Claims revoked = database.revokeBuilder(owner, builder);
        assertFalse(Domain.canBuild(revoked.islands().getFirst(), builder));
        assertTrue(revoked.revision() > invited.revision());
        assertEquals(revoked, database.islands());
        assertThrows(SQLException.class, () -> database.inviteBuilder(stranger, builder));
    }

    @Test void v051MigrationPreservesMoneyCatalogAndCreatesPhysicalProjection() throws SQLException, IOException {
        legacySchema();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema); connection.setAutoCommit(false);
            Migrations.apply(connection, Migrations.load().subList(0, 2), Migrations.resource("/db/schema-fingerprint.sql"));
            connection.commit();
        }
        UUID player = UUID.randomUUID();
        execute("INSERT INTO profiles(player_id,player_name,balance,cultivation_xp) VALUES ('" + player + "','Legacy',18,50)");
        execute("INSERT INTO inventory VALUES ('" + player + "','linh_thach',1)");
        database.initialize();
        assertEquals(5, number("SELECT version FROM schema_version"));
        Domain.PlayerView view = database.view(player, "upgraded");
        assertEquals(18, view.balance()); assertEquals(50, view.xp()); assertEquals(1, view.items().size());
        assertEquals(0, number("SELECT count(*) FROM operations"));
    }

    @Test void failedMigrationRollsBackDdlAndHistory() throws SQLException, IOException {
        legacySchema();
        execute("INSERT INTO operations VALUES (gen_random_uuid(),'unknown:invalid','x',clock_timestamp())");
        assertEquals("22P02", assertThrows(SQLException.class, database::initialize).getSQLState());
        assertEquals(1, number("SELECT version FROM schema_version"));
        assertEquals(0, number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='operations' AND column_name='actor_id'"));
        assertEquals(0, number("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='migration_history'"));
    }

    private UUID contractFixture(UUID owner, long reward) throws SQLException {
        fund(owner, 10);
        database.reserveIsland(owner, "Owner"); database.islandReady(owner);
        UUID contract = UUID.randomUUID();
        database.createContract(contract, owner, reward, Duration.ofHours(1));
        return contract;
    }

    @Test void contractPaymentConservesCurrencyAndLeavesMintAndPdcUntouched() throws SQLException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID();
        mine(builder, 1); Domain.PlayerView items = database.view(builder, "before");
        UUID contract = contractFixture(owner, 12);
        assertEquals(8, balance(owner)); assertEquals(12, number("SELECT sum(balance) FROM contract_escrow_accounts"));
        assertEquals(20, number("SELECT sum(minted) FROM economy_daily"));
        database.acceptContract(contract, builder); assertEquals(0, balance(builder));
        database.submitContract(contract, builder); database.completeContract(contract, owner);
        assertEquals(12, balance(builder)); assertEquals(8, balance(owner));
        assertEquals(0, number("SELECT sum(balance) FROM contract_escrow_accounts"));
        assertEquals(20, number("SELECT sum(minted) FROM economy_daily"));
        assertEquals(0, number("SELECT sum(burned) FROM economy_daily"));
        assertEquals(0, number("SELECT count(*) FROM island_builders"));
        assertEquals(items.items(), database.view(builder, "after").items());
        assertEquals(items.revision(), database.view(builder, "after").revision());
        assertEquals(0, number("SELECT sum(delta) FROM contract_escrow_ledger"));
        assertEquals(balance(builder), scalar("SELECT sum(delta) FROM ledger WHERE player_id=?", builder));
        assertEquals(balance(owner), scalar("SELECT sum(delta) FROM ledger WHERE player_id=?", owner));
        assertEquals(4, number("SELECT count(*) FROM building_history"));
        assertTrue(database.contractHistory(contract).contains("COMPLETE"));
    }

    @Test void concurrentContractSettlementPaysExactlyOnceAndRejectsRefund() throws Exception {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID contract = contractFixture(owner, 12);
        database.register(builder, "Builder"); database.acceptContract(contract, builder); database.submitContract(contract, builder);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls = IntStream.range(0, 8).mapToObj(index -> (Callable<String>) () -> database.completeContract(contract, owner)).toList();
            List<Future<String>> replies = executor.invokeAll(calls);
            String receipt = replies.getFirst().get();
            for (Future<String> reply : replies) assertEquals(receipt, reply.get());
        }
        assertEquals(12, balance(builder));
        assertEquals(1, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE'"));
        assertEquals(1, number("SELECT count(*) FROM building_history WHERE event_type='COMPLETE'"));
        assertThrows(IllegalArgumentException.class, () -> database.abandonContract(contract, builder));
        assertThrows(IllegalArgumentException.class, () -> database.cancelContract(contract, owner));
        assertEquals(0, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='REFUND'"));
    }

    @Test void concurrentBuildersCannotTakeSameContractOrChangeReceiptActor() throws Exception {
        UUID owner = UUID.randomUUID(); UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID();
        UUID contract = contractFixture(owner, 2);
        database.register(first, "First"); database.register(second, "Second");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(() -> acceptAttempt(contract, first), () -> acceptAttempt(contract, second));
            int successes = 0;
            for (Future<Boolean> reply : executor.invokeAll(calls)) if (reply.get()) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, number("SELECT count(*) FROM building_history WHERE event_type='ACCEPT'"));
        Contracts.Job job = database.contractBoard().jobs().getFirst(); UUID accepted = job.builder().orElseThrow();
        UUID rejected = accepted.equals(first) ? second : first;
        assertThrows(IllegalArgumentException.class, () -> database.submitContract(contract, rejected));
        assertEquals(0, balance(first)); assertEquals(0, balance(second));
        assertEquals(2, job.escrow());
    }

    private boolean acceptAttempt(UUID contract, UUID builder) throws SQLException {
        try { database.acceptContract(contract, builder); return true; }
        catch (IllegalArgumentException rejected) { return false; }
    }

    @Test void contractCancellationAndAbandonmentRefundOnlyOnce() throws SQLException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID contract = contractFixture(owner, 8);
        String receipt = database.cancelContract(contract, owner);
        assertEquals(receipt, database.cancelContract(contract, owner)); assertEquals(20, balance(owner));
        UUID active = UUID.randomUUID(); database.createContract(active, owner, 8, Duration.ofHours(1));
        database.register(builder, "Builder"); database.acceptContract(active, builder);
        assertThrows(IllegalArgumentException.class, () -> database.cancelContract(active, owner));
        String abandoned = database.abandonContract(active, builder);
        assertEquals(abandoned, database.abandonContract(active, builder)); assertEquals(20, balance(owner));
        assertEquals(0, balance(builder)); assertEquals(2, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='REFUND'"));
        assertEquals(0, number("SELECT sum(balance) FROM contract_escrow_accounts"));
        assertEquals(20, number("SELECT sum(minted) FROM economy_daily"));
    }

    @Test void contractExpiryRevokesRightsAndRefundsAfterReopeningDatabase() throws SQLException, IOException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID contract = contractFixture(owner, 8);
        database.register(builder, "Builder"); database.acceptContract(contract, builder);
        Contracts.Job active = database.contractBoard().jobs().getFirst();
        assertTrue(Contracts.builderCanBuild(active, builder, active.centerX(), 100, 8, java.time.Instant.now()));
        assertFalse(Contracts.builderCanBuild(active, builder, active.centerX()+2, 100, 8, java.time.Instant.now()));
        execute("UPDATE building_contracts SET expires_at=clock_timestamp()-interval '1 second'");
        try (GameDatabase recovered = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500))) {
            recovered.initialize(); recovered.expireContracts(); recovered.expireContracts();
            Contracts.Job expired = recovered.contractBoard().jobs().getFirst();
            assertEquals(Contracts.Status.EXPIRED, expired.status());
            assertFalse(Contracts.builderCanBuild(expired, builder, expired.centerX(), 100, 8, java.time.Instant.now()));
        }
        assertEquals(20, balance(owner)); assertEquals(0, balance(builder));
        assertEquals(1, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='REFUND'"));
        assertEquals(1, number("SELECT count(*) FROM building_history WHERE event_type='EXPIRE'"));
        assertThrows(IllegalArgumentException.class, () -> database.submitContract(contract, builder));
    }

    @Test void failedContractDepositAndFailedPayoutRollBackAllAccountsAndHistory() throws SQLException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID();
        database.reserveIsland(owner, "Owner"); database.islandReady(owner); UUID insufficient = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> database.createContract(insufficient, owner, 1, Duration.ofHours(1)));
        assertEquals(0, number("SELECT count(*) FROM building_contracts")); assertEquals(0, number("SELECT count(*) FROM contract_escrow_accounts"));
        assertEquals(0, number("SELECT count(*) FROM operations"));
        UUID contract = contractFixture(owner, 8); database.register(builder, "Builder");
        database.acceptContract(contract, builder); database.submitContract(contract, builder);
        execute("CREATE FUNCTION fail_contract_payment() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.reason LIKE 'CONTRACT_PAYMENT:%' THEN RAISE EXCEPTION 'forced payout failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$; CREATE TRIGGER fail_contract_payment BEFORE INSERT ON ledger FOR EACH ROW EXECUTE FUNCTION fail_contract_payment()");
        assertEquals("23514", assertThrows(SQLException.class, () -> database.completeContract(contract, owner)).getSQLState());
        assertEquals(0, balance(builder)); assertEquals(8, number("SELECT sum(balance) FROM contract_escrow_accounts"));
        assertEquals(Contracts.Status.SUBMITTED, database.contractBoard().jobs().getFirst().status());
        assertEquals(0, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE'"));
        assertEquals(0, number("SELECT count(*) FROM building_history WHERE event_type='COMPLETE'"));
        execute("DROP TRIGGER fail_contract_payment ON ledger; DROP FUNCTION fail_contract_payment()");
        database.completeContract(contract, owner); assertEquals(8, balance(builder));
    }

    @Test void contractAuthorityAndImmutableHistoryFailClosed() throws SQLException {
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID stranger = UUID.randomUUID();
        UUID contract = contractFixture(owner, 4); database.register(builder, "Builder"); database.register(stranger, "Stranger");
        assertThrows(IllegalArgumentException.class, () -> database.acceptContract(contract, owner));
        assertThrows(IllegalArgumentException.class, () -> database.cancelContract(contract, stranger));
        assertThrows(IllegalArgumentException.class, () -> database.createContract(UUID.randomUUID(), owner, 2, Duration.ofHours(1)));
        database.acceptContract(contract, builder); database.submitContract(contract, builder);
        assertThrows(IllegalArgumentException.class, () -> database.completeContract(contract, stranger));
        assertThrows(SQLException.class, () -> execute("UPDATE contract_escrow_ledger SET delta=1"));
        assertThrows(SQLException.class, () -> execute("DELETE FROM building_history"));
        assertThrows(SQLException.class, () -> execute("TRUNCATE contract_escrow_ledger CASCADE"));
        assertEquals(4, number("SELECT sum(balance) FROM contract_escrow_accounts"));
    }

    @Test void v06MigrationPreservesIslandPermissionsTokensAndWallet() throws SQLException, IOException {
        legacySchema();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema); connection.setAutoCommit(false);
            Migrations.apply(connection, Migrations.load().subList(0, 3), Migrations.resource("/db/schema-fingerprint.sql")); connection.commit();
        }
        UUID owner = UUID.randomUUID(); UUID builder = UUID.randomUUID();
        execute("INSERT INTO economy_policy VALUES (true,'linh_te',10000,500,'" + CatalogFile.signature(catalog) + "')");
        database.register(builder, "Builder"); fund(owner, 4); database.reserveIsland(owner, "Owner");
        database.islandReady(owner); Domain.Claims claims = database.inviteBuilder(owner, builder);
        mine(owner, 1); Domain.PlayerView before = database.view(owner, "before");
        database.initialize(); database.initialize();
        assertEquals(5, number("SELECT version FROM schema_version")); assertEquals(5, number("SELECT count(*) FROM migration_history"));
        assertEquals(claims, database.islands()); assertEquals(before.items(), database.view(owner, "after").items());
        assertEquals(8, balance(owner)); assertEquals(0, number("SELECT count(*) FROM building_contracts"));
    }

    private Connection lockProfile(UUID player) throws SQLException {
        Connection connection = DriverManager.getConnection(url, user, password);
        connection.setSchema(schema); connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT player_id FROM profiles WHERE player_id=? FOR UPDATE")) {
            statement.setObject(1, player); statement.execute();
        }
        return connection;
    }

    private void awaitBlockedQuery() throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (number("SELECT count(*) FROM pg_stat_activity WHERE application_name='CuuVucCore' AND wait_event_type='Lock'") > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Không quan sát được query bị khóa trong PostgreSQL.");
    }

    @Test void shutdownDrainsRunningTransactionBeforeClosingPool() throws Exception {
        UUID player = UUID.randomUUID(); stockFixture(player, 1);
        try (Connection blocker = lockProfile(player); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> sale = executor.submit(() -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1));
            awaitBlockedQuery();
            Future<GameDatabase.DrainResult> drain = executor.submit(() -> database.drainAndClose(Duration.ofSeconds(3)));
            Thread.sleep(100);
            assertFalse(drain.isDone());
            blocker.commit();
            assertTrue(sale.get(5, TimeUnit.SECONDS).contains("+2"));
            assertTrue(drain.get(5, TimeUnit.SECONDS).drained());
        }
        assertEquals(2, balance(player)); assertEquals(0, stock(player));
        assertEquals("57P01", assertThrows(SQLException.class, () -> database.profile(player)).getSQLState());
    }

    @Test void shutdownTimeoutReportsRequestAndDefersPoolClose() throws Exception {
        UUID player = UUID.randomUUID(); stockFixture(player, 1); UUID request = UUID.randomUUID();
        try (Connection blocker = lockProfile(player); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> sale = executor.submit(() -> database.sell(request, player, "Cultivator", "linh_thach_tho", 1));
            awaitBlockedQuery();
            GameDatabase.DrainResult drain = database.drainAndClose(Duration.ZERO);
            assertFalse(drain.drained()); assertTrue(drain.pending().getFirst().contains(request.toString()));
            assertThrows(SQLException.class, () -> database.profile(player));
            blocker.commit();
            assertTrue(sale.get(5, TimeUnit.SECONDS).contains("+2"));
        }
        assertEquals(2, balance(player));
    }

    @Test void transientSqlFailureRetriesSameRequestExactlyOnce() throws SQLException {
        UUID player = UUID.randomUUID(); stockFixture(player, 1);
        execute("CREATE SEQUENCE fault_attempt; CREATE FUNCTION fail_once() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF nextval('fault_attempt')=1 THEN RAISE EXCEPTION 'fault injection' USING ERRCODE='40001'; END IF; RETURN NEW; END $$; CREATE TRIGGER fault BEFORE UPDATE OF balance ON profiles FOR EACH ROW EXECUTE FUNCTION fail_once()");
        UUID request = UUID.randomUUID(); database.sell(request, player, "Cultivator", "linh_thach_tho", 1);
        assertEquals(2, balance(player)); assertEquals(0, stock(player));
        assertEquals(1, number("SELECT count(*) FROM ledger")); assertEquals(2, number("SELECT last_value FROM fault_attempt"));
        assertEquals(2, number("SELECT sum(minted) FROM economy_daily"));
    }

    @Test void materialTransferRevokesBothProjectionsAndPreservesEconomy() throws SQLException {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID();
        stockFixture(sender, 5); stockFixture(recipient, 2);
        Domain.PlayerView before = database.view(sender, ""); Domain.PlayerView targetBefore = database.view(recipient, "");
        UUID request = UUID.randomUUID();
        String receipt = database.transferMaterials(request, sender, recipient, "linh_thach_tho", 3, before.revision());
        assertEquals(receipt, database.transferMaterials(request, sender, recipient, "linh_thach_tho", 3, before.revision()));
        assertEquals(2, stock(sender)); assertEquals(5, stock(recipient));
        for (Domain.ItemToken token : before.items()) assertEquals(0, scalar("SELECT count(*) FROM item_tokens WHERE token_id=?", token.id()));
        for (Domain.ItemToken token : targetBefore.items()) assertEquals(0, scalar("SELECT count(*) FROM item_tokens WHERE token_id=?", token.id()));
        Domain.PlayerView after = database.view(sender, ""); Domain.PlayerView targetAfter = database.view(recipient, "");
        assertEquals(before.revision() + 1, after.revision()); assertEquals(targetBefore.revision() + 1, targetAfter.revision());
        assertEquals(2, after.items().size()); assertEquals(5, targetAfter.items().size());
        assertEquals(2, number("SELECT count(*) FROM resource_ledger")); assertEquals(0, number("SELECT sum(delta) FROM resource_ledger"));
        assertEquals(7, number("SELECT sum(balance_after) FROM resource_ledger"));
        assertEquals(0, number("SELECT count(*) FROM ledger")); assertEquals(0, number("SELECT count(*) FROM economy_daily"));
        assertTrue(database.materialHistory(recipient).contains(request.toString()));
        assertThrows(SQLException.class, () -> execute("UPDATE resource_ledger SET delta=1"));
        assertThrows(SQLException.class, () -> execute("DELETE FROM resource_ledger"));
        assertThrows(SQLException.class, () -> execute("TRUNCATE resource_ledger"));
    }

    @Test void materialReplayConcurrentAndReopenCreditsOnce() throws Exception {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); stockFixture(sender, 8); database.register(recipient, "Recipient");
        long revision = database.view(sender, "").revision(); UUID request = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> actions = IntStream.range(0, 8).mapToObj(index -> (Callable<String>) () -> database.transferMaterials(request, sender, recipient, "linh_thach_tho", 4, revision)).toList();
            List<Future<String>> results = executor.invokeAll(actions);
            for (Future<String> result : results) assertTrue(result.get(10, TimeUnit.SECONDS).contains(request.toString()));
        }
        assertEquals(4, stock(sender)); assertEquals(4, stock(recipient)); assertEquals(2, number("SELECT count(*) FROM resource_ledger"));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(request, sender, recipient, "linh_thach_tho", 3, revision));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(request, recipient, sender, "linh_thach_tho", 4, revision));
        database.close(); database = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500)); database.initialize();
        assertTrue(database.transferMaterials(request, sender, recipient, "linh_thach_tho", 4, revision).contains(request.toString()));
        assertEquals(4, stock(recipient)); assertEquals(2, number("SELECT count(*) FROM resource_ledger"));
    }

    @Test void distinctMaterialTransfersCannotSpendSameSnapshotTwice() throws Exception {
        UUID sender = UUID.randomUUID(); UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID();
        stockFixture(sender, 5); database.register(first, "First"); database.register(second, "Second");
        long revision = database.view(sender, "").revision();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> actions = List.of(
                    () -> materialAttempt(sender, first, revision), () -> materialAttempt(sender, second, revision));
            List<Future<Boolean>> results = executor.invokeAll(actions);
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(2, stock(sender)); assertEquals(3, stock(first) + stock(second));
        assertEquals(2, number("SELECT count(*) FROM resource_ledger"));
    }

    private boolean materialAttempt(UUID sender, UUID recipient, long revision) throws SQLException {
        try { database.transferMaterials(UUID.randomUUID(), sender, recipient, "linh_thach_tho", 3, revision); return true; }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Kho đã thay đổi")); return false; }
    }

    @Test void materialValidationAndChangedStockLeaveNoPartialTransfer() throws SQLException {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); stockFixture(sender, 5); database.register(recipient, "Recipient");
        long revision = database.view(sender, "").revision();
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(UUID.randomUUID(), sender, sender, "linh_thach_tho", 1, revision));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(UUID.randomUUID(), sender, UUID.randomUUID(), "linh_thach_tho", 1, revision));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(UUID.randomUUID(), sender, recipient, "unknown", 1, revision));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(UUID.randomUUID(), sender, recipient, "linh_thach_tho", 0, revision));
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(UUID.randomUUID(), sender, recipient, "linh_thach_tho", 6, revision));
        database.craft(UUID.randomUUID(), sender, "Cultivator", "ket_tinh", 1);
        UUID stale = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> database.transferMaterials(stale, sender, recipient, "linh_thach_tho", 1, revision));
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", stale));
        assertEquals(0, stock(recipient)); assertEquals(0, number("SELECT count(*) FROM resource_ledger"));
    }

    @Test void materialLedgerFailureRollsBackCreditDebitTokensAndReceipt() throws SQLException {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); stockFixture(sender, 5); stockFixture(recipient, 2);
        Domain.PlayerView before = database.view(sender, ""); Domain.PlayerView targetBefore = database.view(recipient, "");
        execute("CREATE FUNCTION fail_material_credit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.delta>0 THEN RAISE EXCEPTION 'forced material ledger failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$; CREATE TRIGGER fail_material_credit BEFORE INSERT ON resource_ledger FOR EACH ROW EXECUTE FUNCTION fail_material_credit()");
        UUID request = UUID.randomUUID();
        assertEquals("23514", assertThrows(SQLException.class, () -> database.transferMaterials(request, sender, recipient, "linh_thach_tho", 3, before.revision())).getSQLState());
        assertEquals(5, stock(sender)); assertEquals(2, stock(recipient));
        assertEquals(before, database.view(sender, "")); assertEquals(targetBefore, database.view(recipient, ""));
        assertEquals(0, number("SELECT count(*) FROM resource_ledger")); assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request));
    }

    @Test void materialTransferIntoLargeOfflineStockKeepsProjectionBounded() throws SQLException {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); stockFixture(sender, 5); stockFixture(recipient, 100);
        long revision = database.view(sender, "").revision();
        database.transferMaterials(UUID.randomUUID(), sender, recipient, "linh_thach_tho", 5, revision);
        assertEquals(105, stock(recipient)); assertEquals(0, stock(sender));
        assertEquals(0, scalar("SELECT count(*) FROM item_tokens WHERE player_id=?", recipient));
        Domain.PlayerView recipientView = database.view(recipient, "");
        assertEquals(105L, recipientView.stock().get("linh_thach_tho")); assertEquals(36, recipientView.items().size());
        assertEquals(105, stock(recipient));
    }

    @Test void v07MigrationPreservesExistingStockTokensAndReceipts() throws SQLException, IOException {
        database.close();
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE"); statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema); connection.setAutoCommit(false);
            Migrations.apply(connection, Migrations.load().subList(0, 4), Migrations.resource("/db/schema-fingerprint.sql")); connection.commit();
        }
        UUID sender = UUID.randomUUID(); UUID token = UUID.randomUUID(); UUID request = UUID.randomUUID();
        execute("INSERT INTO profiles(player_id,player_name,balance) VALUES ('" + sender + "','Legacy',10); INSERT INTO inventory VALUES ('" + sender + "','linh_thach_tho',5); INSERT INTO item_tokens(token_id,player_id,resource_id,ordinal) VALUES ('" + token + "','" + sender + "','linh_thach_tho',1); INSERT INTO operations(request_id,fingerprint,result,actor_id) VALUES ('" + request + "','legacy','preserved','" + sender + "')");
        database = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500)); database.initialize();
        assertEquals(5, number("SELECT version FROM schema_version")); assertEquals(5, stock(sender)); assertEquals(10, balance(sender));
        assertEquals(1, scalar("SELECT count(*) FROM item_tokens WHERE token_id=?", token)); assertTrue(database.receipt(sender, request).contains("preserved"));
        assertEquals(0, number("SELECT count(*) FROM resource_ledger"));
        database.initialize();
    }

    @Test void retriesExhaustWithoutPartialInventoryOrMint() throws SQLException {
        UUID player = UUID.randomUUID(); stockFixture(player, 1);
        execute("CREATE SEQUENCE fault_attempt; CREATE FUNCTION fail_always() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('fault_attempt'); RAISE EXCEPTION 'fault injection' USING ERRCODE='40001'; END $$; CREATE TRIGGER fault BEFORE UPDATE OF balance ON profiles FOR EACH ROW EXECUTE FUNCTION fail_always()");
        SQLException failure = assertThrows(SQLException.class, () -> database.sell(UUID.randomUUID(), player, "Cultivator", "linh_thach_tho", 1));
        assertEquals("40001", failure.getSQLState());
        assertEquals(3, number("SELECT last_value FROM fault_attempt")); assertEquals(1, stock(player));
        assertEquals(0, balance(player)); assertEquals(0, number("SELECT count(*) FROM economy_daily"));
    }
}
