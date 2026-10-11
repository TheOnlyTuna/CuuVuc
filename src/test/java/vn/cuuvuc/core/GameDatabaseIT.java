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

    private UUID treasuryFixture(UUID leader, UUID elder) throws SQLException {
        database.register(leader, "Leader"); database.register(elder, "Elder");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "Treasury Sect");
        UUID invitation = UUID.randomUUID(); database.inviteSect(invitation, leader, sect, elder);
        database.acceptSect(UUID.randomUUID(), elder, invitation);
        database.changeSectRole(UUID.randomUUID(), leader, sect, elder, Sects.Role.ELDER);
        return sect;
    }

    private long treasuryBalance(UUID sect) throws SQLException {
        return scalar("SELECT balance FROM sect_accounts WHERE sect_id=?", sect);
    }

    private void assertMoneyConserved(long expected) throws SQLException {
        assertEquals(expected, number("SELECT (SELECT coalesce(sum(balance),0) FROM profiles)+(SELECT coalesce(sum(balance),0) FROM sect_accounts)+(SELECT coalesce(sum(balance),0) FROM contract_escrow_accounts)"));
        assertEquals(expected, number("SELECT coalesce(sum(minted-burned),0) FROM economy_daily"));
        assertEquals(expected, number("SELECT (SELECT coalesce(sum(delta),0) FROM ledger)+(SELECT coalesce(sum(delta),0) FROM contract_escrow_ledger)"));
    }

    @Test void treasuryTransfersShareLedgerPreserveMintAndReplayReceipts() throws SQLException, IOException {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID outsider = UUID.randomUUID();
        UUID sect = treasuryFixture(leader, elder); fund(leader, 10); fund(elder, 5); database.register(outsider, "Outsider");
        UUID contribution = UUID.randomUUID(); String receipt = database.contributeTreasury(contribution, leader, sect, 12);
        assertEquals(receipt, database.contributeTreasury(contribution, leader, sect, 12));
        assertThrows(IllegalArgumentException.class, () -> database.contributeTreasury(contribution, leader, sect, 11));
        assertThrows(IllegalArgumentException.class, () -> database.contributeTreasury(UUID.randomUUID(), outsider, sect, 1));
        database.contributeTreasury(UUID.randomUUID(), elder, sect, 4);
        assertThrows(IllegalArgumentException.class, () -> database.withdrawTreasury(UUID.randomUUID(), elder, sect, 1));
        database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder);
        UUID withdrawal = UUID.randomUUID(); String withdrawn = database.withdrawTreasury(withdrawal, elder, sect, 6);
        database.revokeTreasurySpend(UUID.randomUUID(), leader, sect, elder);
        assertEquals(withdrawn, database.withdrawTreasury(withdrawal, elder, sect, 6));
        assertThrows(IllegalArgumentException.class, () -> database.withdrawTreasury(UUID.randomUUID(), elder, sect, 1));
        assertEquals(10, treasuryBalance(sect)); assertEquals(8, balance(leader)); assertEquals(12, balance(elder));
        assertEquals(10, scalar("SELECT sum(delta) FROM ledger WHERE sect_id=?", sect));
        assertMoneyConserved(30);
        database.initialize(); assertEquals(10, database.treasuryView(leader).balance());
        assertThrows(SQLException.class, () -> execute("UPDATE ledger SET reason='tampered' WHERE sect_id='" + sect + "'"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO ledger(request_id,player_id,sect_id,delta,balance_after,reason) VALUES ('" + UUID.randomUUID() + "',NULL,NULL,1,1,'invalid')"));
    }

    @Test void treasuryRoleChangesAndDepartureRevokeSpendingWithoutRestoringOldGrants() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        fund(leader, 5); database.contributeTreasury(UUID.randomUUID(), leader, sect, 10);
        UUID grant = UUID.randomUUID(); String receipt = database.grantTreasurySpend(grant, leader, sect, elder);
        assertTrue(database.treasuryView(elder).spending());
        assertThrows(IllegalArgumentException.class, () -> database.grantTreasurySpend(UUID.randomUUID(), elder, sect, leader));
        database.changeSectRole(UUID.randomUUID(), leader, sect, elder, Sects.Role.MEMBER);
        database.changeSectRole(UUID.randomUUID(), leader, sect, elder, Sects.Role.ELDER);
        assertFalse(database.treasuryView(elder).spending());
        assertEquals(receipt, database.grantTreasurySpend(grant, leader, sect, elder));
        assertFalse(database.treasuryView(elder).spending());
        assertThrows(IllegalArgumentException.class, () -> database.withdrawTreasury(UUID.randomUUID(), elder, sect, 1));
        database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder); database.leaveSect(UUID.randomUUID(), elder, sect);
        UUID invitation = UUID.randomUUID(); database.inviteSect(invitation, leader, sect, elder); database.acceptSect(UUID.randomUUID(), elder, invitation);
        database.changeSectRole(UUID.randomUUID(), leader, sect, elder, Sects.Role.ELDER);
        assertFalse(database.treasuryView(elder).spending());
        database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder); database.kickSectMember(UUID.randomUUID(), leader, sect, elder);
        assertThrows(IllegalArgumentException.class, () -> database.withdrawTreasury(UUID.randomUUID(), elder, sect, 1)); assertMoneyConserved(10);
    }

    @Test void withdrawalWaitsForSectLockAndRejectsRevokedCapturedIntent() throws Exception {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        fund(leader, 5); database.contributeTreasury(UUID.randomUUID(), leader, sect, 10); database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder);
        Treasury.View old = database.treasuryView(elder); assertTrue(old.spending()); UUID request = UUID.randomUUID();
        try (Connection blocker = DriverManager.getConnection(url, user, password); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setSchema(schema); blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("SELECT sect_id FROM sects FOR UPDATE");
                statement.execute("UPDATE sect_members SET treasury_spend=false WHERE player_id='" + elder + "'");
            }
            Future<String> result = executor.submit(() -> database.withdrawTreasury(request, elder, old.sect(), 2)); awaitBlockedQuery(); blocker.commit();
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request)); assertEquals(10, treasuryBalance(sect)); assertMoneyConserved(10);
    }

    @Test void concurrentTreasuryWithdrawalsAndRepeatedRequestsCannotOverspend() throws Exception {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        fund(leader, 20); database.contributeTreasury(UUID.randomUUID(), leader, sect, 40); UUID request = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> repeated = IntStream.range(0, 12).mapToObj(index -> (Callable<String>) () -> database.withdrawTreasury(request, leader, sect, 4)).toList();
            List<Future<String>> receipts = executor.invokeAll(repeated); String receipt = receipts.getFirst().get();
            for (Future<String> result : receipts) assertEquals(receipt, result.get());
            List<Callable<Boolean>> expenses = IntStream.range(0, 6).mapToObj(index -> (Callable<Boolean>) () -> {
                try { database.withdrawTreasury(UUID.randomUUID(), leader, sect, 12); return true; }
                catch (IllegalArgumentException failure) { assertTrue(failure.getMessage().contains("không đủ Linh Tệ")); return false; }
            }).toList();
            int paid = 0; for (Future<Boolean> result : executor.invokeAll(expenses)) if (result.get()) paid++;
            assertEquals(3, paid);
        }
        assertEquals(0, treasuryBalance(sect)); assertEquals(2, scalar("SELECT count(*) FROM ledger WHERE request_id=?", request)); assertMoneyConserved(40);
    }

    @Test void failedTreasuryHistoryOrLedgerRollsBackBothSidesAndReceipt() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder); fund(leader, 5);
        execute("CREATE FUNCTION fail_treasury() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Treasury fault' USING ERRCODE='23514'; END $$");
        execute("CREATE TRIGGER fault BEFORE INSERT ON sect_history FOR EACH ROW EXECUTE FUNCTION fail_treasury()");
        UUID request = UUID.randomUUID(); assertThrows(SQLException.class, () -> database.contributeTreasury(request, leader, sect, 6));
        assertEquals(10, balance(leader)); assertEquals(0, treasuryBalance(sect)); assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request));
        execute("DROP TRIGGER fault ON sect_history"); database.contributeTreasury(request, leader, sect, 6);
        execute("CREATE TRIGGER fault BEFORE INSERT ON ledger FOR EACH ROW WHEN (NEW.player_id IS NOT NULL) EXECUTE FUNCTION fail_treasury()");
        UUID withdrawal = UUID.randomUUID(); assertThrows(SQLException.class, () -> database.withdrawTreasury(withdrawal, leader, sect, 2));
        assertEquals(4, balance(leader)); assertEquals(6, treasuryBalance(sect)); assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", withdrawal)); assertMoneyConserved(10);
    }

    @Test void sectContractDepositAndWithdrawalCompeteForTheSameFundsAtomically() throws Exception {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        fund(leader, 10); database.contributeTreasury(UUID.randomUUID(), leader, sect, 20);
        database.reserveIsland(leader, "Leader"); database.islandReady(leader); UUID contract = UUID.randomUUID();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> work = List.of(
                    () -> competingTreasurySpend(() -> database.createSectContract(contract, leader, sect, 8, Duration.ofHours(1))),
                    () -> competingTreasurySpend(() -> database.withdrawTreasury(UUID.randomUUID(), leader, sect, 18)));
            int successes = 0; for (Future<Boolean> result : executor.invokeAll(work)) if (result.get()) successes++;
            assertEquals(1, successes);
        }
        assertMoneyConserved(20);
        long contracts = number("SELECT count(*) FROM building_contracts");
        assertEquals(contracts, number("SELECT count(*) FROM contract_escrow_ledger"));
        assertEquals(contracts, number("SELECT count(*) FROM sect_history WHERE event_type='CONTRACT_DEPOSIT'"));
        assertEquals(contracts == 1 ? 12 : 2, treasuryBalance(sect));
    }

    private static boolean competingTreasurySpend(PlayerTasks.DatabaseAction action) throws SQLException {
        try { action.execute(); return true; }
        catch (IllegalArgumentException failure) { assertTrue(failure.getMessage().contains("không đủ Linh Tệ")); return false; }
    }

    @Test void sectContractsPayBuilderAndRefundOriginalTreasuryThroughAllSettlementPaths() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        database.register(builder, "Builder"); fund(leader, 10); database.contributeTreasury(UUID.randomUUID(), leader, sect, 20);
        database.reserveIsland(leader, "Leader"); database.islandReady(leader); UUID paid = UUID.randomUUID();
        String deposited = database.createSectContract(paid, leader, sect, 8, Duration.ofHours(1));
        assertEquals(deposited, database.createSectContract(paid, leader, sect, 8, Duration.ofHours(1))); assertEquals(0, balance(leader)); assertMoneyConserved(20);
        database.acceptContract(paid, builder); database.submitContract(paid, builder);
        String receipt = database.completeContract(paid, leader); assertEquals(receipt, database.completeContract(paid, leader));
        assertEquals(8, balance(builder)); assertEquals(12, treasuryBalance(sect));
        UUID cancelled = UUID.randomUUID(); database.createSectContract(cancelled, leader, sect, 4, Duration.ofHours(1)); database.cancelContract(cancelled, leader);
        UUID abandoned = UUID.randomUUID(); database.createSectContract(abandoned, leader, sect, 2, Duration.ofHours(1)); database.acceptContract(abandoned, builder); database.abandonContract(abandoned, builder);
        UUID expired = UUID.randomUUID(); database.createSectContract(expired, leader, sect, 2, Duration.ofHours(1));
        execute("UPDATE building_contracts SET expires_at=clock_timestamp()-interval '1 second' WHERE contract_id='" + expired + "'"); database.expireContracts(); database.expireContracts();
        assertEquals(12, treasuryBalance(sect)); assertEquals(0, balance(leader)); assertEquals(8, balance(builder)); assertMoneyConserved(20);
        assertEquals(3, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='REFUND'")); assertEquals(1, number("SELECT count(*) FROM contract_escrow_ledger WHERE kind='RELEASE'"));
        assertEquals(3, number("SELECT count(*) FROM sect_history WHERE event_type='CONTRACT_REFUND'"));
    }

    @Test void revokedContractCreatorCannotSpendCancelOrApproveButCurrentLeaderCanSettle() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID builder = UUID.randomUUID(); UUID sect = treasuryFixture(leader, elder);
        fund(leader, 10); database.contributeTreasury(UUID.randomUUID(), leader, sect, 20); database.register(builder, "Builder");
        database.reserveIsland(elder, "Elder"); database.islandReady(elder); database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder);
        UUID first = UUID.randomUUID(); database.createSectContract(first, elder, sect, 4, Duration.ofHours(1)); database.revokeTreasurySpend(UUID.randomUUID(), leader, sect, elder);
        assertThrows(IllegalArgumentException.class, () -> database.cancelContract(first, elder)); database.cancelContract(first, leader);
        UUID rejected = UUID.randomUUID(); assertThrows(IllegalArgumentException.class, () -> database.createSectContract(rejected, elder, sect, 4, Duration.ofHours(1)));
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", Contracts.request(rejected, "CREATE")));
        database.grantTreasurySpend(UUID.randomUUID(), leader, sect, elder); UUID second = UUID.randomUUID(); database.createSectContract(second, elder, sect, 6, Duration.ofHours(1));
        database.acceptContract(second, builder); database.submitContract(second, builder); database.kickSectMember(UUID.randomUUID(), leader, sect, elder);
        assertThrows(IllegalArgumentException.class, () -> database.completeContract(second, elder)); assertEquals(0, balance(builder));
        database.completeContract(second, leader); assertEquals(6, balance(builder)); assertEquals(14, treasuryBalance(sect)); assertMoneyConserved(20);
    }

    @Test void v06MigrationAddsZeroTreasuryAndPreservesExistingWalletLedgerAndEscrow() throws SQLException, IOException {
        legacySchema();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema); connection.setAutoCommit(false);
            Migrations.apply(connection, Migrations.load().subList(0, 6), Migrations.resource("/db/schema-fingerprint.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO profiles(player_id,player_name,balance) VALUES ('00000000-0000-0000-0000-000000000001','Leader',12)");
                statement.execute("INSERT INTO operations(request_id,actor_id,fingerprint,result) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','legacy','ok')");
                statement.execute("INSERT INTO ledger(request_id,player_id,delta,balance_after,reason) VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',12,12,'legacy')");
                statement.execute("INSERT INTO sects(sect_id,name,name_key,leader_id) VALUES ('00000000-0000-0000-0000-000000000003','Legacy Sect','legacy sect','00000000-0000-0000-0000-000000000001')");
                statement.execute("INSERT INTO sect_members(player_id,sect_id,role) VALUES ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','LEADER')");
                statement.execute("INSERT INTO islands(player_id,ready) VALUES ('00000000-0000-0000-0000-000000000001',true)");
                statement.execute("INSERT INTO building_contracts(contract_id,owner_id,island_slot,reward,status,expires_at) VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001',1,8,'OPEN',clock_timestamp()+interval '1 hour')");
                statement.execute("INSERT INTO contract_escrow_accounts VALUES ('00000000-0000-0000-0000-000000000004','linh_te',8)");
            }
            connection.commit();
        }
        database.initialize(); database.initialize();
        assertEquals(7, number("SELECT version FROM schema_version")); assertEquals(12, number("SELECT sum(balance) FROM profiles"));
        assertEquals(0, number("SELECT sum(balance) FROM sect_accounts")); assertEquals(12, number("SELECT sum(delta) FROM ledger"));
        assertEquals(0, number("SELECT count(*) FROM ledger WHERE sect_id IS NOT NULL")); assertEquals(1, number("SELECT count(*) FROM sect_members"));
        assertEquals(8, number("SELECT sum(balance) FROM contract_escrow_accounts"));
        assertTrue(database.contractBoard().jobs().getFirst().fundingSect().isEmpty());
        database.cancelContract(UUID.fromString("00000000-0000-0000-0000-000000000004"), UUID.fromString("00000000-0000-0000-0000-000000000001"));
        assertEquals(20, number("SELECT sum(balance) FROM profiles")); assertEquals(0, number("SELECT sum(balance) FROM sect_accounts"));
    }

    @Test void sectLifecyclePersistsRolesHistoryAndLeavesEconomyUntouched() throws SQLException, IOException {
        UUID leader = UUID.randomUUID(); UUID member = UUID.randomUUID(); UUID next = UUID.randomUUID(); UUID sect = UUID.randomUUID();
        database.register(leader, "Leader"); database.register(member, "Member"); database.register(next, "Next");
        String created = database.createSect(sect, leader, "Thanh Vân");
        assertEquals(created, database.createSect(sect, leader, "Thanh Vân"));
        UUID invitation = UUID.randomUUID(); database.inviteSect(invitation, leader, sect, member);
        database.acceptSect(UUID.randomUUID(), member, invitation);
        database.changeSectRole(UUID.randomUUID(), leader, sect, member, Sects.Role.ELDER);
        UUID second = UUID.randomUUID(); database.inviteSect(second, member, sect, next);
        database.cancelSectInvitation(UUID.randomUUID(), member, second);
        database.changeSectRole(UUID.randomUUID(), leader, sect, member, Sects.Role.MEMBER);
        assertThrows(IllegalArgumentException.class, () -> database.inviteSect(UUID.randomUUID(), member, sect, next));
        assertThrows(IllegalArgumentException.class, () -> database.changeSectRole(UUID.randomUUID(), member, sect, member, Sects.Role.ELDER));
        assertThrows(IllegalArgumentException.class, () -> database.leaveSect(UUID.randomUUID(), leader, sect));
        assertThrows(IllegalArgumentException.class, () -> database.kickSectMember(UUID.randomUUID(), leader, sect, leader));
        assertThrows(IllegalArgumentException.class, () -> database.changeSectRole(UUID.randomUUID(), leader, sect, member, Sects.Role.LEADER));
        database.leaveSect(UUID.randomUUID(), member, sect);
        UUID rejoin = UUID.randomUUID(); database.inviteSect(rejoin, leader, sect, member); database.acceptSect(UUID.randomUUID(), member, rejoin);
        database.kickSectMember(UUID.randomUUID(), leader, sect, member);
        assertEquals(1, number("SELECT count(*) FROM sect_members"));
        assertEquals(11, number("SELECT count(*) FROM sect_history"));
        assertEquals(11, number("SELECT count(*) FROM operations"));
        assertEquals(11, number("SELECT revision FROM sects"));
        assertEquals(0, number("SELECT count(*) FROM ledger")); assertEquals(0, number("SELECT count(*) FROM resource_ledger"));
        assertEquals(0, number("SELECT count(*) FROM economy_daily"));
        assertThrows(SQLException.class, () -> execute("UPDATE sect_history SET detail='tampered'"));
        assertThrows(SQLException.class, () -> execute("DELETE FROM sect_history"));
        assertThrows(SQLException.class, () -> execute("TRUNCATE sect_history"));
        try (GameDatabase reopened = new GameDatabase(url, user, password, schema, catalog, new Domain.EconomyPolicy(10000, 500))) {
            reopened.initialize(); assertEquals(sect, reopened.sectView(leader).organization().orElseThrow().id());
            assertEquals(10, reopened.sectView(leader).history().size()); assertTrue(reopened.sectView(member).organization().isEmpty());
        }
    }

    @Test void concurrentAcceptsForDifferentSectsGiveExactlyOneMembership() throws Exception {
        UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID(); UUID target = UUID.randomUUID();
        database.register(first, "First"); database.register(second, "Second"); database.register(target, "Target");
        UUID sectA = UUID.randomUUID(); UUID sectB = UUID.randomUUID(); database.createSect(sectA, first, "Sect Alpha"); database.createSect(sectB, second, "Sect Beta");
        UUID inviteA = UUID.randomUUID(); UUID inviteB = UUID.randomUUID(); database.inviteSect(inviteA, first, sectA, target); database.inviteSect(inviteB, second, sectB, target);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = executor.invokeAll(List.of(
                    () -> competingSectJoin(() -> database.acceptSect(UUID.randomUUID(), target, inviteA)),
                    () -> competingSectJoin(() -> database.acceptSect(UUID.randomUUID(), target, inviteB))));
            int successes = 0; for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, scalar("SELECT count(*) FROM sect_members WHERE player_id=?", target));
        assertEquals(1, number("SELECT count(*) FROM sect_invitations WHERE status='ACCEPTED'"));
        assertEquals(5, number("SELECT count(*) FROM operations")); assertEquals(5, number("SELECT count(*) FROM sect_history"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO sect_members(player_id,sect_id,role) VALUES ('" + target + "','" + sectA + "','MEMBER')"));
    }

    @Test void creatingSectAndAcceptingInvitationRaceCannotCreateTwoMemberships() throws Exception {
        UUID leader = UUID.randomUUID(); UUID target = UUID.randomUUID(); database.register(leader, "Leader"); database.register(target, "Target");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "Existing Sect");
        UUID invitation = UUID.randomUUID(); database.inviteSect(invitation, leader, sect, target);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = executor.invokeAll(List.of(
                    () -> competingSectJoin(() -> database.acceptSect(UUID.randomUUID(), target, invitation)),
                    () -> competingSectJoin(() -> database.createSect(UUID.randomUUID(), target, "New Sect"))));
            int successes = 0; for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, scalar("SELECT count(*) FROM sect_members WHERE player_id=?", target));
        assertEquals(3, number("SELECT count(*) FROM operations")); assertEquals(3, number("SELECT count(*) FROM sect_history"));
    }

    private static boolean competingSectJoin(PlayerTasks.DatabaseAction action) throws SQLException {
        try { action.execute(); return true; }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("đã thuộc một tông môn")); return false; }
    }

    @Test void revokedAuthorityRejectsCapturedIntentAfterWaitingForSectLock() throws Exception {
        UUID leader = UUID.randomUUID(); UUID elder = UUID.randomUUID(); UUID target = UUID.randomUUID();
        database.register(leader, "Leader"); database.register(elder, "Elder"); database.register(target, "Target");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "Authority Sect");
        UUID invitation = UUID.randomUUID(); database.inviteSect(invitation, leader, sect, elder); database.acceptSect(UUID.randomUUID(), elder, invitation);
        database.changeSectRole(UUID.randomUUID(), leader, sect, elder, Sects.Role.ELDER);
        Sects.View oldSnapshot = database.sectView(elder); assertEquals(Sects.Role.ELDER, oldSnapshot.organization().orElseThrow().role());
        UUID capturedRequest = UUID.randomUUID();
        try (Connection blocker = DriverManager.getConnection(url, user, password); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setSchema(schema); blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("SELECT sect_id FROM sects FOR UPDATE");
                statement.execute("UPDATE sect_members SET role='MEMBER' WHERE player_id='" + elder + "'");
            }
            Future<String> result = executor.submit(() -> database.inviteSect(capturedRequest, elder, oldSnapshot.organization().orElseThrow().id(), target));
            awaitBlockedQuery();
            blocker.commit();
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalArgumentException); assertTrue(failure.getCause().getMessage().contains("quyền quản sự"));
        }
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", capturedRequest));
        assertEquals(1, number("SELECT count(*) FROM sect_invitations")); assertEquals(4, number("SELECT count(*) FROM sect_history"));
        database.kickSectMember(UUID.randomUUID(), leader, sect, elder);
        assertThrows(IllegalArgumentException.class, () -> database.cancelSectInvitation(UUID.randomUUID(), elder, invitation));
    }

    @Test void sectInvitationExpiryRecipientCancellationAndReplayAreEnforced() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID target = UUID.randomUUID(); UUID stranger = UUID.randomUUID();
        database.register(leader, "Leader"); database.register(target, "Target"); database.register(stranger, "Stranger");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "Invite Sect"); UUID invitation = UUID.randomUUID();
        String invited = database.inviteSect(invitation, leader, sect, target); assertEquals(invited, database.inviteSect(invitation, leader, sect, target));
        assertThrows(IllegalArgumentException.class, () -> database.inviteSect(invitation, leader, sect, stranger));
        assertThrows(IllegalArgumentException.class, () -> database.acceptSect(UUID.randomUUID(), stranger, invitation));
        assertThrows(IllegalArgumentException.class, () -> database.inviteSect(UUID.randomUUID(), leader, sect, target));
        execute("UPDATE sect_invitations SET expires_at=clock_timestamp()-interval '1 second'");
        assertTrue(database.sectView(target).incoming().isEmpty()); assertThrows(IllegalArgumentException.class, () -> database.acceptSect(UUID.randomUUID(), target, invitation));
        UUID fresh = UUID.randomUUID(); database.inviteSect(fresh, leader, sect, target); database.cancelSectInvitation(UUID.randomUUID(), leader, fresh);
        assertThrows(IllegalArgumentException.class, () -> database.acceptSect(UUID.randomUUID(), target, fresh));
        UUID valid = UUID.randomUUID(); database.inviteSect(valid, leader, sect, target); UUID accept = UUID.randomUUID();
        String joined = database.acceptSect(accept, target, valid); assertEquals(joined, database.acceptSect(accept, target, valid));
        assertThrows(IllegalArgumentException.class, () -> database.acceptSect(UUID.randomUUID(), target, valid));
        assertEquals(6, number("SELECT count(*) FROM operations")); assertEquals(6, number("SELECT count(*) FROM sect_history"));
    }

    @Test void sectHistoryFailureRollsBackInvitationReceiptAndRevision() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID target = UUID.randomUUID(); database.register(leader, "Leader"); database.register(target, "Target");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "Rollback Sect");
        execute("CREATE FUNCTION fail_sect_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture event failure'; END $$; CREATE TRIGGER fail_sect_event BEFORE INSERT ON sect_history FOR EACH ROW EXECUTE FUNCTION fail_sect_event()");
        UUID request = UUID.randomUUID(); assertThrows(SQLException.class, () -> database.inviteSect(request, leader, sect, target));
        assertEquals(0, number("SELECT count(*) FROM sect_invitations")); assertEquals(1, number("SELECT revision FROM sects"));
        assertEquals(0, scalar("SELECT count(*) FROM operations WHERE request_id=?", request)); assertEquals(1, number("SELECT count(*) FROM sect_history"));
    }

    @Test void v08PhaseOneMigrationPreservesMaterialsTokensReceiptsAndWallet() throws SQLException, IOException {
        legacySchema();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setSchema(schema); connection.setAutoCommit(false);
            Migrations.apply(connection, Migrations.load().subList(0, 5), Migrations.resource("/db/schema-fingerprint.sql")); connection.commit();
        }
        execute("INSERT INTO economy_policy VALUES (true,'linh_te',10000,500,'" + CatalogFile.signature(catalog) + "')");
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); fund(sender, 4); database.register(recipient, "Recipient");
        mine(sender, 2); long revision = database.view(sender, "before").revision(); UUID transfer = UUID.randomUUID();
        String receipt = database.transferMaterials(transfer, sender, recipient, "linh_thach_tho", 2, revision);
        Domain.PlayerView before = database.view(recipient, "before");
        database.initialize(); database.initialize();
        assertEquals(7, number("SELECT version FROM schema_version")); assertEquals(7, number("SELECT count(*) FROM migration_history"));
        assertEquals(before.items(), database.view(recipient, "after").items()); assertEquals(8, balance(sender));
        assertEquals(receipt, database.transferMaterials(transfer, sender, recipient, "linh_thach_tho", 2, revision));
        assertEquals(2, number("SELECT count(*) FROM resource_ledger")); assertEquals(0, number("SELECT count(*) FROM sects"));
    }

    @Test void sectNamesAndLeaderConstraintFailClosed() throws SQLException {
        UUID leader = UUID.randomUUID(); UUID second = UUID.randomUUID(); database.register(leader, "Leader"); database.register(second, "Second");
        UUID sect = UUID.randomUUID(); database.createSect(sect, leader, "  Thanh   Vân  ");
        assertThrows(IllegalArgumentException.class, () -> database.createSect(UUID.randomUUID(), second, "thanh vân"));
        assertThrows(IllegalArgumentException.class, () -> database.createSect(UUID.randomUUID(), second, "x"));
        assertThrows(SQLException.class, () -> execute("UPDATE sect_members SET role='MEMBER' WHERE player_id='" + leader + "'"));
        assertEquals(Sects.Role.LEADER, database.sectView(leader).organization().orElseThrow().role());
        assertEquals(1, number("SELECT count(*) FROM sects")); assertEquals(1, number("SELECT count(*) FROM operations"));
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
                catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("không đủ Linh Tệ")); return false; }
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
        assertEquals(7, number("SELECT version FROM schema_version"));
        assertEquals(7, number("SELECT count(*) FROM migration_history"));
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
        assertEquals(7, number("SELECT version FROM schema_version"));
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
        assertEquals(7, number("SELECT version FROM schema_version")); assertEquals(7, number("SELECT count(*) FROM migration_history"));
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

    @Test void craftSellAndTransferRaceConsumesOneStockWithoutPartialTransactions() throws Exception {
        UUID sender = UUID.randomUUID(); UUID recipient = UUID.randomUUID(); stockFixture(sender, 5); database.register(recipient, "Recipient");
        long revision = database.view(sender, "").revision();
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Callable<String>> actions = List.of(
                    () -> competingMaterialAction(() -> database.craft(UUID.randomUUID(), sender, "Cultivator", "ket_tinh", 1), "craft"),
                    () -> competingMaterialAction(() -> database.sell(UUID.randomUUID(), sender, "Cultivator", "linh_thach_tho", 5), "sell"),
                    () -> competingMaterialAction(() -> database.transferMaterials(UUID.randomUUID(), sender, recipient, "linh_thach_tho", 5, revision), "transfer"));
            List<Future<String>> results = executor.invokeAll(actions);
            int successes = 0;
            for (Future<String> result : results) if (!result.get(10, TimeUnit.SECONDS).equals("rejected")) successes++;
            assertEquals(1, successes);
        }
        assertEquals(0, scalar("SELECT quantity FROM inventory WHERE player_id=? AND resource_id='linh_thach_tho'", sender));
        long crafted = scalar("SELECT coalesce(sum(quantity),0) FROM inventory WHERE player_id=? AND resource_id='linh_thach'", sender);
        long sold = balance(sender) / 2;
        assertEquals(5, crafted * 5 + sold + stock(recipient));
        assertEquals(1, number("SELECT count(*) FROM operations"));
        assertEquals(stock(recipient) == 5 ? 2 : 0, number("SELECT count(*) FROM resource_ledger"));
        assertEquals(sold == 5 ? 1 : 0, number("SELECT count(*) FROM ledger"));
        assertEquals(balance(sender), number("SELECT coalesce(sum(minted),0) FROM economy_daily"));
    }

    private static String competingMaterialAction(PlayerTasks.DatabaseAction action, String result) throws SQLException {
        try { action.execute(); return result; }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Không đủ nguyên liệu") || expected.getMessage().contains("Kho đã thay đổi"));
            return "rejected";
        }
    }

    @Test void materialConstraintRoundTripPreservesFingerprintButWrongBoundFailsClosed() throws SQLException, IOException {
        String definition;
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement statement = connection.createStatement()) {
            connection.setSchema(schema);
            try (ResultSet result = statement.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='resource_ledger'::regclass AND conname='resource_ledger_delta_check'")) {
                assertTrue(result.next()); definition = result.getString(1);
            }
            statement.execute("ALTER TABLE resource_ledger DROP CONSTRAINT resource_ledger_delta_check");
            statement.execute("ALTER TABLE resource_ledger ADD CONSTRAINT resource_ledger_delta_check " + definition);
        }
        database.initialize();
        execute("ALTER TABLE resource_ledger DROP CONSTRAINT resource_ledger_delta_check; ALTER TABLE resource_ledger ADD CONSTRAINT resource_ledger_delta_check CHECK (delta>=-1000 AND delta<=1001 AND delta<>0)");
        assertEquals("55000", assertThrows(SQLException.class, database::initialize).getSQLState());
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
        assertEquals(7, number("SELECT version FROM schema_version")); assertEquals(5, stock(sender)); assertEquals(10, balance(sender));
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
