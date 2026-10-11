package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Một cổng Economy cho ví PLAYER và SECT; bên gọi giữ khóa hồ sơ hoặc tông môn. */
public interface WalletLedger {
    enum Type { PLAYER, SECT }
    record Account(Type type, UUID id) { }

    static void change(Connection connection, UUID request, Account account, long delta, String reason) throws SQLException {
        if (delta == 0) throw new IllegalArgumentException("Giao dịch Linh Tệ phải khác 0.");
        String update = switch (account.type()) {
            case PLAYER -> "UPDATE profiles SET balance=balance+? WHERE player_id=? AND balance+?>=0 RETURNING balance";
            case SECT -> "UPDATE sect_accounts SET balance=balance+? WHERE sect_id=? AND balance+?>=0 RETURNING balance";
        };
        long balance;
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setLong(1, delta); statement.setObject(2, account.id()); statement.setLong(3, delta);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Tài khoản " + account.type() + " " + account.id() + " không tồn tại hoặc không đủ Linh Tệ.");
                balance = result.getLong(1);
            }
        }
        String insert = switch (account.type()) {
            case PLAYER -> "INSERT INTO ledger(request_id,player_id,delta,balance_after,reason) VALUES (?,?,?,?,?)";
            case SECT -> "INSERT INTO ledger(request_id,sect_id,delta,balance_after,reason) VALUES (?,?,?,?,?)";
        };
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            statement.setObject(1, request); statement.setObject(2, account.id());
            statement.setLong(3, delta); statement.setLong(4, balance); statement.setString(5, reason); statement.executeUpdate();
        }
    }
}
