package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Cổng dữ liệu tông môn; khóa tông môn trước hồ sơ, quyền luôn đọc lại trong transaction. */
public interface Sects {
    enum Role { LEADER, ELDER, MEMBER }
    record Name(String display, String key) { }
    record Organization(UUID id, String name, long revision, Role role) { }
    record Member(UUID player, String name, Role role) { }
    record Invitation(UUID id, UUID sect, String name, UUID recipient, Instant expiresAt) { }
    record Event(long id, UUID actor, String actorName, UUID target, String targetName, String type, String detail, Instant at) { }
    record View(Optional<Organization> organization, List<Member> members, List<Invitation> incoming,
                List<Invitation> outgoing, List<Event> history) {
        public View { members = List.copyOf(members); incoming = List.copyOf(incoming); outgoing = List.copyOf(outgoing); history = List.copyOf(history); }
    }

    static Name name(String input) {
        String display = Normalizer.normalize(input, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        if (!display.matches("[\\p{L}\\p{M}\\p{N} _-]{3,32}"))
            throw new IllegalArgumentException("Tên tông môn cần 3–32 chữ, số, khoảng trắng, dấu _ hoặc -.");
        return new Name(display, display.toLowerCase(Locale.ROOT));
    }

    static String create(Connection connection, UUID request, UUID founder, Name name) throws SQLException {
        GameDatabase.lockPlayer(connection, founder);
        requireUnaffiliated(connection, founder);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sects(sect_id,name,name_key,leader_id) VALUES (?,?,?,?) ON CONFLICT(name_key) DO NOTHING")) {
            statement.setObject(1, request); statement.setString(2, name.display()); statement.setString(3, name.key()); statement.setObject(4, founder);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Tên tông môn đã được sử dụng.");
        }
        addMember(connection, request, founder, Role.LEADER);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sect_accounts(sect_id) VALUES (?)")) {
            statement.setObject(1, request); statement.executeUpdate();
        }
        event(connection, request, request, founder, founder, "CREATE", name.display());
        return "Đã khai sơn " + name.display() + ". Tông môn: " + request;
    }

    static String invite(Connection connection, UUID request, UUID actor, UUID sect, UUID recipient) throws SQLException {
        lockSect(connection, sect); requireManager(connection, sect, actor);
        GameDatabase.lockPlayer(connection, recipient); requireUnaffiliated(connection, recipient);
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sect_invitations WHERE sect_id=? AND recipient_id=? AND status='PENDING' AND expires_at>clock_timestamp()")) {
            statement.setObject(1, sect); statement.setObject(2, recipient);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new IllegalArgumentException("Người này đã có lời mời còn hiệu lực từ tông môn.");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sect_invitations(invitation_id,sect_id,inviter_id,recipient_id,status,expires_at) VALUES (?,?,?,?,'PENDING',clock_timestamp()+interval '1 hour')")) {
            statement.setObject(1, request); statement.setObject(2, sect); statement.setObject(3, actor); statement.setObject(4, recipient); statement.executeUpdate();
        }
        event(connection, request, sect, actor, recipient, "INVITE", "Lời mời hiệu lực một giờ: " + request);
        return "Đã mời " + recipient + ". Lời mời: " + request;
    }

    static String accept(Connection connection, UUID request, UUID actor, UUID invitation) throws SQLException {
        UUID sect = invitationSect(connection, invitation);
        lockSect(connection, sect); GameDatabase.lockPlayer(connection, actor);
        requireUnaffiliated(connection, actor);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sect_invitations SET status='ACCEPTED' WHERE invitation_id=? AND recipient_id=? AND status='PENDING' AND expires_at>clock_timestamp()")) {
            statement.setObject(1, invitation); statement.setObject(2, actor);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Lời mời không thuộc bạn, đã hết hạn, bị hủy hoặc đã nhận.");
        }
        addMember(connection, sect, actor, Role.MEMBER);
        event(connection, request, sect, actor, actor, "ACCEPT", "Nhận lời mời " + invitation);
        return "Đã gia nhập tông môn " + sect;
    }

    static String changeRole(Connection connection, UUID request, UUID actor, UUID sect, UUID target, Role role) throws SQLException {
        if (role == Role.LEADER) throw new IllegalArgumentException("Prototype chưa hỗ trợ chuyển chức chưởng môn.");
        lockSect(connection, sect); requireLeader(connection, sect, actor);
        GameDatabase.lockPlayer(connection, target);
        Role previous = role(connection, sect, target);
        if (previous == Role.LEADER) throw new IllegalArgumentException("Không thể đổi quyền chưởng môn.");
        if (previous == role) throw new IllegalArgumentException("Thành viên đã có quyền này.");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sect_members SET role=?,treasury_spend=false WHERE sect_id=? AND player_id=?")) {
            statement.setString(1, role.name()); statement.setObject(2, sect); statement.setObject(3, target); statement.executeUpdate();
        }
        event(connection, request, sect, actor, target, "ROLE", previous + " → " + role);
        return "Đã đổi quyền " + target + " thành " + role;
    }

    static String kick(Connection connection, UUID request, UUID actor, UUID sect, UUID target) throws SQLException {
        lockSect(connection, sect); requireLeader(connection, sect, actor); GameDatabase.lockPlayer(connection, target);
        if (role(connection, sect, target) == Role.LEADER) throw new IllegalArgumentException("Không thể loại chưởng môn.");
        deleteMember(connection, sect, target);
        event(connection, request, sect, actor, target, "KICK", "Loại khỏi tông môn");
        return "Đã loại " + target + " khỏi tông môn.";
    }

    static String leave(Connection connection, UUID request, UUID actor, UUID sect) throws SQLException {
        lockSect(connection, sect); GameDatabase.lockPlayer(connection, actor);
        if (role(connection, sect, actor) == Role.LEADER) throw new IllegalArgumentException("Chưởng môn chưa thể rời hoặc giải tán tông môn trong prototype.");
        deleteMember(connection, sect, actor);
        event(connection, request, sect, actor, actor, "LEAVE", "Rời tông môn");
        return "Đã rời tông môn.";
    }

    static String cancelInvitation(Connection connection, UUID request, UUID actor, UUID invitation) throws SQLException {
        UUID sect = invitationSect(connection, invitation);
        lockSect(connection, sect); requireManager(connection, sect, actor);
        UUID recipient;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sect_invitations SET status='CANCELLED' WHERE invitation_id=? AND status='PENDING' AND expires_at>clock_timestamp() RETURNING recipient_id")) {
            statement.setObject(1, invitation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Lời mời đã nhận, hết hạn hoặc bị hủy.");
                recipient = result.getObject(1, UUID.class);
            }
        }
        event(connection, request, sect, actor, recipient, "CANCEL_INVITE", "Hủy lời mời " + invitation);
        return "Đã hủy lời mời " + invitation;
    }

    static View view(Connection connection, UUID player) throws SQLException {
        Optional<Organization> organization = Optional.empty();
        try (PreparedStatement statement = connection.prepareStatement("SELECT s.sect_id,s.name,s.revision,m.role FROM sect_members m JOIN sects s USING(sect_id) WHERE m.player_id=?")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) organization = Optional.of(new Organization(result.getObject(1, UUID.class), result.getString(2), result.getLong(3), Role.valueOf(result.getString(4))));
            }
        }
        List<Member> members = new ArrayList<>(); List<Event> history = new ArrayList<>(); List<Invitation> outgoing = List.of();
        if (organization.isPresent()) {
            Organization sect = organization.orElseThrow();
            try (PreparedStatement statement = connection.prepareStatement("SELECT m.player_id,p.player_name,m.role FROM sect_members m JOIN profiles p USING(player_id) WHERE m.sect_id=? ORDER BY m.joined_at,m.player_id")) {
                statement.setObject(1, sect.id());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) members.add(new Member(result.getObject(1, UUID.class), result.getString(2), Role.valueOf(result.getString(3))));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT h.entry_id,h.actor_id,a.player_name,h.target_id,t.player_name,h.event_type,h.detail,h.created_at FROM sect_history h JOIN profiles a ON a.player_id=h.actor_id JOIN profiles t ON t.player_id=h.target_id WHERE h.sect_id=? ORDER BY h.entry_id DESC LIMIT 10")) {
                statement.setObject(1, sect.id());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) history.add(new Event(result.getLong(1), result.getObject(2, UUID.class), result.getString(3), result.getObject(4, UUID.class), result.getString(5), result.getString(6), result.getString(7), result.getTimestamp(8).toInstant()));
                }
            }
            if (sect.role() != Role.MEMBER) outgoing = invitations(connection, "SELECT i.invitation_id,i.sect_id,s.name,i.recipient_id,i.expires_at FROM sect_invitations i JOIN sects s USING(sect_id) WHERE i.sect_id=? AND i.status='PENDING' AND i.expires_at>clock_timestamp() ORDER BY i.created_at DESC LIMIT 9", sect.id());
        }
        List<Invitation> incoming = invitations(connection, "SELECT i.invitation_id,i.sect_id,s.name,i.recipient_id,i.expires_at FROM sect_invitations i JOIN sects s USING(sect_id) WHERE i.recipient_id=? AND i.status='PENDING' AND i.expires_at>clock_timestamp() ORDER BY i.created_at DESC LIMIT 9", player);
        return new View(organization, members, incoming, outgoing, history);
    }

    private static List<Invitation> invitations(Connection connection, String sql, UUID subject) throws SQLException {
        List<Invitation> invitations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, subject);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) invitations.add(new Invitation(result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getString(3), result.getObject(4, UUID.class), result.getTimestamp(5).toInstant()));
            }
        }
        return List.copyOf(invitations);
    }

    private static UUID invitationSect(Connection connection, UUID invitation) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sect_id FROM sect_invitations WHERE invitation_id=?")) {
            statement.setObject(1, invitation);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không có lời mời: " + invitation);
                return result.getObject(1, UUID.class);
            }
        }
    }

    static void lockSect(Connection connection, UUID sect) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sect_id FROM sects WHERE sect_id=? FOR UPDATE")) {
            statement.setObject(1, sect);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Không có tông môn: " + sect);
            }
        }
    }


    private static void requireUnaffiliated(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT sect_id FROM sect_members WHERE player_id=?")) {
            statement.setObject(1, player);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) throw new IllegalArgumentException("Người chơi đã thuộc một tông môn; không thể tham gia hai tông môn.");
            }
        }
    }

    static Role role(Connection connection, UUID sect, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT role FROM sect_members WHERE sect_id=? AND player_id=?")) {
            statement.setObject(1, sect); statement.setObject(2, player);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("Bạn hoặc người đích không còn là thành viên tông môn này.");
                return Role.valueOf(result.getString(1));
            }
        }
    }

    private static void requireManager(Connection connection, UUID sect, UUID actor) throws SQLException {
        if (role(connection, sect, actor) == Role.MEMBER) throw new IllegalArgumentException("Cần quyền quản sự hoặc chưởng môn hiện tại trong DB.");
    }

    static void requireLeader(Connection connection, UUID sect, UUID actor) throws SQLException {
        if (role(connection, sect, actor) != Role.LEADER) throw new IllegalArgumentException("Chỉ chưởng môn hiện tại được đổi quyền hoặc loại thành viên.");
    }

    private static void addMember(Connection connection, UUID sect, UUID player, Role role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sect_members(player_id,sect_id,role) VALUES (?,?,?)")) {
            statement.setObject(1, player); statement.setObject(2, sect); statement.setString(3, role.name()); statement.executeUpdate();
        }
    }

    private static void deleteMember(Connection connection, UUID sect, UUID player) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sect_members WHERE sect_id=? AND player_id=?")) {
            statement.setObject(1, sect); statement.setObject(2, player); statement.executeUpdate();
        }
    }

    static void event(Connection connection, UUID request, UUID sect, UUID actor, UUID target, String type, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE sects SET revision=revision+1 WHERE sect_id=?")) {
            statement.setObject(1, sect); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO sect_history(sect_id,request_id,actor_id,target_id,event_type,detail) VALUES (?,?,?,?,?,?)")) {
            statement.setObject(1, sect); statement.setObject(2, request); statement.setObject(3, actor); statement.setObject(4, target); statement.setString(5, type); statement.setString(6, detail); statement.executeUpdate();
        }
    }
}
