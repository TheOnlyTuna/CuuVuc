package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Ngân khố dùng chung WalletLedger; quyền chi và thay đổi thành viên cùng khóa tông môn. */
public interface Treasury {
    record Member(UUID id, String name, Sects.Role role, boolean spending) { }
    record View(UUID sect, String name, long balance, long wallet, boolean spending, boolean leader,
                List<Member> members, List<String> history) {
        public View { members = List.copyOf(members); history = List.copyOf(history); }
    }

    static View view(Connection connection, UUID actor) throws SQLException {
        UUID sect; String name; long balance; long wallet; boolean spending; boolean leader;
        try (PreparedStatement statement = connection.prepareStatement("SELECT s.sect_id,s.name,a.balance,p.balance,m.role,m.treasury_spend FROM sect_members m JOIN sects s USING(sect_id) JOIN sect_accounts a USING(sect_id) JOIN profiles p USING(player_id) WHERE m.player_id=?")) {
            statement.setObject(1, actor);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Bạn chưa thuộc tông môn.");
                sect = result.getObject(1, UUID.class); name = result.getString(2); balance = result.getLong(3); wallet = result.getLong(4);
                leader = result.getString(5).equals("LEADER"); spending = leader || result.getBoolean(6);
            }
        }
        List<Member> members = new ArrayList<>(); List<String> history = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT m.player_id,p.player_name,m.role,m.treasury_spend FROM sect_members m JOIN profiles p USING(player_id) WHERE m.sect_id=? ORDER BY m.joined_at,m.player_id")) {
            statement.setObject(1, sect);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) members.add(new Member(result.getObject(1, UUID.class), result.getString(2), Sects.Role.valueOf(result.getString(3)), result.getBoolean(4)));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT delta,balance_after,reason FROM ledger WHERE sect_id=? ORDER BY entry_id DESC LIMIT 10")) {
            statement.setObject(1, sect);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) history.add(result.getLong(1) + " | còn " + result.getLong(2) + " | " + result.getString(3));
            }
        }
        return new View(sect, name, balance, wallet, spending, leader, members, history);
    }

    static void requireSpender(Connection connection, UUID sect, UUID actor) throws SQLException {
        Sects.Role role = Sects.role(connection, sect, actor);
        if (role == Sects.Role.LEADER) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT treasury_spend FROM sect_members WHERE sect_id=? AND player_id=?")) {
            statement.setObject(1, sect); statement.setObject(2, actor);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || role != Sects.Role.ELDER || !result.getBoolean(1))
                    throw new IllegalArgumentException("Quyền chi ngân khố đã bị thu hồi hoặc chưa được cấp trong DB.");
            }
        }
    }

    static String grant(Connection connection, UUID request, UUID actor, UUID sect, UUID target) throws SQLException {
        Sects.lockSect(connection, sect); Sects.requireLeader(connection, sect, actor);
        if (Sects.role(connection, sect, target) != Sects.Role.ELDER) throw new IllegalArgumentException("Chỉ cấp quyền chi riêng cho quản sự ELDER.");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sect_members SET treasury_spend=true WHERE sect_id=? AND player_id=? AND NOT treasury_spend")) {
            statement.setObject(1, sect); statement.setObject(2, target);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Quản sự đã có quyền chi.");
        }
        Sects.event(connection, request, sect, actor, target, "SPEND_GRANT", "Cấp quyền chi Linh Tệ");
        return "Đã cấp quyền chi ngân khố cho " + target;
    }

    static String revoke(Connection connection, UUID request, UUID actor, UUID sect, UUID target) throws SQLException {
        Sects.lockSect(connection, sect); Sects.requireLeader(connection, sect, actor); Sects.role(connection, sect, target);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sect_members SET treasury_spend=false WHERE sect_id=? AND player_id=? AND treasury_spend")) {
            statement.setObject(1, sect); statement.setObject(2, target);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Thành viên không có quyền chi riêng.");
        }
        Sects.event(connection, request, sect, actor, target, "SPEND_REVOKE", "Thu hồi quyền chi Linh Tệ");
        return "Đã thu hồi quyền chi ngân khố của " + target;
    }

    static String contribute(Connection connection, UUID request, UUID actor, UUID sect, long amount) throws SQLException {
        Sects.lockSect(connection, sect); Sects.role(connection, sect, actor); GameDatabase.lockPlayer(connection, actor);
        WalletLedger.change(connection, request, new WalletLedger.Account(WalletLedger.Type.PLAYER, actor), -amount, "SECT_CONTRIBUTE:" + sect);
        WalletLedger.change(connection, request, new WalletLedger.Account(WalletLedger.Type.SECT, sect), amount, "SECT_CONTRIBUTE:" + actor);
        Sects.event(connection, request, sect, actor, actor, "CONTRIBUTE", "Góp " + amount + " Linh Tệ");
        return "Đã góp " + amount + " Linh Tệ vào ngân khố.";
    }

    static String withdraw(Connection connection, UUID request, UUID actor, UUID sect, long amount) throws SQLException {
        Sects.lockSect(connection, sect); requireSpender(connection, sect, actor); GameDatabase.lockPlayer(connection, actor);
        WalletLedger.change(connection, request, new WalletLedger.Account(WalletLedger.Type.SECT, sect), -amount, "SECT_WITHDRAW:" + actor);
        WalletLedger.change(connection, request, new WalletLedger.Account(WalletLedger.Type.PLAYER, actor), amount, "SECT_WITHDRAW:" + sect);
        Sects.event(connection, request, sect, actor, actor, "WITHDRAW", "Rút " + amount + " Linh Tệ về ví người chi");
        return "Đã rút " + amount + " Linh Tệ về ví của bạn.";
    }
}
