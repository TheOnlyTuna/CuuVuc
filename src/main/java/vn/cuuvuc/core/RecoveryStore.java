package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Connector PostgreSQL: quyền, snapshot và quyết định cùng khóa quản trị rồi khóa hợp đồng. */
public interface RecoveryStore {
    static void authority(Connection connection, UUID player, boolean enabled, String reason) throws SQLException {
        RecoveryResolution.requireReason(reason);
        lockAuthority(connection);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO recovery_authorities(player_id,enabled,revision) VALUES (?,?,1) ON CONFLICT(player_id) DO UPDATE SET enabled=EXCLUDED.enabled,revision=recovery_authorities.revision+1 RETURNING revision")) {
            statement.setObject(1, player); statement.setBoolean(2, enabled);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next(); long revision = rows.getLong(1);
                try (PreparedStatement history = connection.prepareStatement("INSERT INTO recovery_authority_history(player_id,enabled,revision,reason) VALUES (?,?,?,?)")) {
                    history.setObject(1, player); history.setBoolean(2, enabled); history.setLong(3, revision); history.setString(4, reason); history.executeUpdate();
                }
            }
        }
    }

    private static void lockAuthority(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute("SELECT pg_advisory_xact_lock(5080510)"); }
    }

    static void requireAuthority(Connection connection, UUID actor) throws SQLException {
        lockAuthority(connection);
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM recovery_authorities WHERE player_id=? AND enabled")) {
            statement.setObject(1, actor);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Quyền phục hồi PostgreSQL đã bị thu hồi hoặc chưa được console cấp: " + actor);
            }
        }
    }

    static RecoveryResolution.Evidence evidence(Connection connection, UUID actor, UUID contract) throws SQLException {
        requireAuthority(connection, actor);
        Contracts.Job job = GameDatabase.lockContractRecord(connection, contract);
        String canonical;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT jsonb_build_object(
                    'authority',(SELECT to_jsonb(a) FROM recovery_authorities a WHERE player_id=?),
                    'contract',to_jsonb(c),'escrow',to_jsonb(e),'fence',to_jsonb(f),
                    'journal',(SELECT coalesce(jsonb_agg(to_jsonb(j) ORDER BY j.event_id),'[]') FROM contract_block_journal j WHERE j.contract_id=c.contract_id),
                    'receipts',(SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY o.request_id),'[]') FROM operations o WHERE o.request_id IN (SELECT event_id FROM contract_block_journal WHERE contract_id=c.contract_id)),
                    'audit',(SELECT coalesce(jsonb_agg(to_jsonb(h) ORDER BY h.event_id),'[]') FROM building_history h WHERE h.contract_id=c.contract_id),
                    'escrow_ledger',(SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.entry_id),'[]') FROM contract_escrow_ledger l WHERE l.contract_id=c.contract_id),
                    'stage',(SELECT to_jsonb(s) FROM project_stages s WHERE s.stage_id=c.project_stage_id),
                    'reserve',(SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.resource_id),'[]') FROM stage_inventory i WHERE i.stage_id=c.project_stage_id),
                    'decisions',(SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY d.review_id),'[]') FROM recovery_decisions d JOIN recovery_reviews r USING(review_id) WHERE r.contract_id=c.contract_id)
                )::text FROM building_contracts c JOIN contract_escrow_accounts e USING(contract_id)
                JOIN contract_recovery_fences f USING(contract_id) WHERE c.contract_id=?
                """)) {
            statement.setObject(1, actor); statement.setObject(2, contract);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Hợp đồng không còn recovery fence: " + contract);
                canonical = rows.getString(1);
            }
        }
        List<GameDatabase.BlockAudit> audit = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT ON(detail) contract_id,detail,event_type,event_id FROM building_history WHERE contract_id=? AND event_type IN ('PLACE','BREAK') ORDER BY detail,event_id DESC")) {
            statement.setObject(1, contract);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) audit.add(new GameDatabase.BlockAudit(contract, rows.getString(2), rows.getString(3), rows.getLong(4)));
            }
        }
        return new RecoveryResolution.Evidence(job, GameDatabase.readBlockJournal(connection).stream().filter(row -> row.entry().contract().equals(contract)).toList(), audit, canonical);
    }

    static String createReview(Connection connection, RecoveryResolution.Review review) throws SQLException {
        RecoveryResolution.Evidence current = evidence(connection, review.actor(), review.evidence().job().id());
        if (!current.canonical().equals(review.evidence().canonical())) throw new IllegalArgumentException("Bằng chứng DB đã thay đổi; tạo review mới.");
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO recovery_reviews(review_id,contract_id,actor_id,evidence,world_observation) VALUES (?,?,?,?,?) ON CONFLICT(review_id) DO NOTHING")) {
            statement.setObject(1, review.id()); statement.setObject(2, current.job().id()); statement.setObject(3, review.actor());
            statement.setString(4, current.canonical()); statement.setString(5, RecoveryResolution.worldText(review.world())); statement.executeUpdate();
        }
        Stored saved = stored(connection, review.actor(), review.id());
        if (!saved.contract().equals(current.job().id()) || !saved.evidence().equals(current.canonical()) || !saved.world().equals(RecoveryResolution.worldText(review.world())))
            throw new IllegalArgumentException("Review ID đã dùng cho nội dung khác: " + review.id());
        return "Review " + review.id() + ": chỉ quan sát, fence vẫn giữ. Đọc hồ sơ trước khi hold/resolve với lý do.";
    }

    record Stored(UUID contract, String evidence, String world, boolean fresh) { }

    static Stored stored(Connection connection, UUID actor, UUID review) throws SQLException {
        requireAuthority(connection, actor);
        try (PreparedStatement statement = connection.prepareStatement("SELECT contract_id,evidence,world_observation,created_at > clock_timestamp()-interval '10 minutes' FROM recovery_reviews WHERE review_id=? AND actor_id=?")) {
            statement.setObject(1, review); statement.setObject(2, actor);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Review không tồn tại hoặc không thuộc quản trị viên hiện tại: " + review);
                return new Stored(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getBoolean(4));
            }
        }
    }

    static RecoveryResolution.Evidence reviewedEvidence(Connection connection, UUID actor, UUID review) throws SQLException {
        Stored stored = stored(connection, actor, review);
        return evidence(connection, actor, stored.contract());
    }

    static Optional<String> committedDecision(Connection connection, UUID actor, UUID review, RecoveryResolution.Decision decision, String reason) throws SQLException {
        RecoveryResolution.requireReason(reason);
        stored(connection, actor, review);
        try (PreparedStatement statement = connection.prepareStatement("SELECT decision,reason FROM recovery_decisions WHERE review_id=?")) {
            statement.setObject(1, review);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (!rows.getString(1).equals(decision.name()) || !rows.getString(2).equals(reason))
                    throw new IllegalArgumentException("Review đã có quyết định khác, không được sửa lịch sử: " + review);
                return Optional.of("Quyết định đã commit: " + review + " / " + decision);
            }
        }
    }

    static String decide(Connection connection, UUID actor, UUID review, RecoveryResolution.Decision decision, String reason, List<RecoveryResolution.Observation> world) throws SQLException {
        RecoveryResolution.requireReason(reason);
        Stored stored = stored(connection, actor, review);
        GameDatabase.lockContractRecord(connection, stored.contract());
        stored = stored(connection, actor, review);
        try (PreparedStatement statement = connection.prepareStatement("SELECT decision,reason FROM recovery_decisions WHERE review_id=?")) {
            statement.setObject(1, review);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (!rows.getString(1).equals(decision.name()) || !rows.getString(2).equals(reason))
                        throw new IllegalArgumentException("Review đã có quyết định khác, không được sửa lịch sử: " + review);
                    return "Quyết định đã commit: " + review + " / " + decision;
                }
            }
        }
        RecoveryResolution.Evidence current = evidence(connection, actor, stored.contract());
        if (!stored.fresh() || !stored.evidence().equals(current.canonical()))
            throw new IllegalArgumentException("Review hết hạn hoặc DB/quyền/fence đã thay đổi; tạo review mới.");
        String checkedWorld = decision == RecoveryResolution.Decision.KEEP_LOCKED ? stored.world() : RecoveryResolution.worldText(world);
        if (decision == RecoveryResolution.Decision.RESOLVE_INTENTS_AND_REOPEN) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM unrecoverable_cases c LEFT JOIN unrecoverable_decisions d USING(case_id) WHERE c.contract_id=? AND (d.case_id IS NULL OR d.outcome='APPROVED') LIMIT 1")) {
                statement.setObject(1, stored.contract());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) throw new IllegalArgumentException("Hồ sơ UNRECOVERABLE giữ vùng cách ly; resolve không được vượt quyết định này.");
                }
            }
            if (!stored.world().equals(checkedWorld)) throw new IllegalArgumentException("World đã thay đổi sau review; giữ fence và tạo review mới.");
            RecoveryResolution.requireReopenable(current, world);
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM contract_recovery_fences WHERE contract_id=? AND reason LIKE 'legacy pending%' UNION ALL SELECT 1 FROM building_history h WHERE h.contract_id=? AND h.event_type IN ('PLACE','BREAK') AND NOT EXISTS(SELECT 1 FROM contract_block_journal j WHERE j.audit_event_id=h.event_id AND j.state='APPLIED') LIMIT 1")) {
                statement.setObject(1, stored.contract()); statement.setObject(2, stored.contract());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) throw new IllegalArgumentException("Có bằng chứng queue/audit cũ chưa liên kết journal; cần xử lý thủ công.");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO recovery_decisions(review_id,actor_id,decision,reason,checked_world) VALUES (?,?,?,?,?)")) {
            statement.setObject(1, review); statement.setObject(2, actor); statement.setString(3, decision.name()); statement.setString(4, reason); statement.setString(5, checkedWorld); statement.executeUpdate();
        }
        if (decision == RecoveryResolution.Decision.RESOLVE_INTENTS_AND_REOPEN) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE contract_block_journal SET state='REVIEWED_UNCONFIRMED',resolved_at=clock_timestamp(),resolution_review_id=? WHERE contract_id=? AND state='INTENT'")) {
                statement.setObject(1, review); statement.setObject(2, stored.contract()); statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM contract_recovery_fences WHERE contract_id=?")) {
                statement.setObject(1, stored.contract());
                if (statement.executeUpdate() != 1) throw new SQLException("Fence mất trong lúc quyết định: " + stored.contract(), "55000");
            }
            try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE contract_state SET revision=revision+1 WHERE singleton"); }
        }
        return "Đã ghi quyết định bất biến: " + review + " / " + decision + ". Không sinh PLACE/BREAK hoặc chuyển tài sản.";
    }

    static String report(Connection connection, UUID actor, UUID review) throws SQLException {
        stored(connection, actor, review);
        try (PreparedStatement statement = connection.prepareStatement("SELECT row_to_json(r)::text,(SELECT row_to_json(d)::text FROM recovery_decisions d WHERE d.review_id=r.review_id) FROM recovery_reviews r WHERE review_id=?")) {
            statement.setObject(1, review);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1) + "\nDECISION=" + rows.getString(2); }
        }
    }
}
