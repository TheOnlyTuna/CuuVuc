package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Tài khoản tài nguyên dùng chung ledger; bên gọi giữ khóa tông môn và hồ sơ nếu cần. */
public interface ResourceLedger {
    enum Type { PLAYER, PROJECT, STAGE }
    record Account(Type type, UUID id) { }
    static long change(Connection connection, Account account, String resource, long delta) throws SQLException {
        if (delta == 0) throw new IllegalArgumentException("Delta tài nguyên phải khác 0.");
        if (account.type() == Type.PLAYER) {
            GameDatabase.changeInventory(connection, account.id(), resource, delta);
            return balance(connection, account, resource);
        }
        String table = account.type() == Type.PROJECT ? "project_inventory" : "stage_inventory";
        String key = column(account.type());
        String sql = delta > 0
                ? "INSERT INTO " + table + "(" + key + ",resource_id,quantity) VALUES (?,?,?) ON CONFLICT(" + key + ",resource_id) DO UPDATE SET quantity=" + table + ".quantity+EXCLUDED.quantity RETURNING quantity"
                : "UPDATE " + table + " SET quantity=quantity+? WHERE " + key + "=? AND resource_id=? AND quantity>=? RETURNING quantity";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (delta > 0) { statement.setObject(1, account.id()); statement.setString(2, resource); statement.setLong(3, delta); }
            else { statement.setLong(1, delta); statement.setObject(2, account.id()); statement.setString(3, resource); statement.setLong(4, -delta); }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không đủ " + resource + " tại " + account.type() + " " + account.id());
                return result.getLong(1);
            }
        }
    }
    static long balance(Connection connection, Account account, String resource) throws SQLException {
        String table = switch (account.type()) { case PLAYER -> "inventory"; case PROJECT -> "project_inventory"; case STAGE -> "stage_inventory"; };
        try (PreparedStatement statement = connection.prepareStatement("SELECT quantity FROM " + table + " WHERE " + column(account.type()) + "=? AND resource_id=?")) {
            statement.setObject(1, account.id()); statement.setString(2, resource);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        }
    }
    static String column(Type type) { return switch (type) { case PLAYER -> "player_id"; case PROJECT -> "project_id"; case STAGE -> "stage_id"; }; }
    static void entry(Connection connection, UUID request, UUID actor, Account owner, Optional<Account> counterparty, String resource, long delta, long balance) throws SQLException {
        String counterpartColumn = counterparty.map(value -> switch (value.type()) {
            case PLAYER -> "counterparty_id"; case PROJECT -> "counterparty_project_id"; case STAGE -> "counterparty_stage_id";
        }).orElse("counterparty_id");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO resource_ledger(request_id,actor_id," + column(owner.type()) + "," + counterpartColumn + ",resource_id,delta,balance_after,kind) VALUES (?,?,?,?,?,?,?,?)")) {
            statement.setObject(1, request); statement.setObject(2, actor); statement.setObject(3, owner.id());
            statement.setObject(4, counterparty.map(Account::id).orElse(null)); statement.setString(5, resource);
            statement.setLong(6, delta); statement.setLong(7, balance); statement.setString(8, counterparty.isPresent() ? "TRANSFER" : "CONSUME"); statement.executeUpdate();
        }
    }
    static void transfer(Connection connection, UUID request, UUID actor, Account source, Account target, String resource, long amount) throws SQLException {
        long left = change(connection, source, resource, -amount); long right = change(connection, target, resource, amount);
        entry(connection, request, actor, source, Optional.of(target), resource, -amount, left);
        entry(connection, request, actor, target, Optional.of(source), resource, amount, right);
    }
}
