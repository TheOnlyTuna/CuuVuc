package vn.cuuvuc.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Connector hồ sơ bất biến: quyền quản trị, hợp đồng rồi tài khoản; không sửa journal/world/fence. */
public interface UnrecoverableStore {
    static String propose(Connection connection, UnrecoverableResolution.Proposal proposal) throws SQLException {
        RecoveryResolution.requireReason(proposal.reason());
        UnrecoverableResolution.requirePlan(proposal.plan(), proposal.compensation());
        RecoveryStore.Stored review = RecoveryStore.stored(connection, proposal.actor(), proposal.review());
        RecoveryResolution.Evidence evidence = RecoveryStore.evidence(connection, proposal.actor(), review.contract());
        if (!review.fresh() || !review.evidence().equals(evidence.canonical())) throw new IllegalArgumentException("Review đã cũ; đọc lại bằng chứng và tạo review mới.");
        requireUnrecoverable(evidence);
        lockParticipants(connection, evidence.job());
        if (proposal.plan() == UnrecoverableResolution.Plan.SETTLE_HELD_ESCROW) requireFundedSettlement(connection, evidence.job(), proposal.compensation());
        try (PreparedStatement statement = connection.prepareStatement("SELECT case_id FROM unrecoverable_cases c WHERE c.contract_id=? AND NOT EXISTS(SELECT 1 FROM unrecoverable_decisions d WHERE d.case_id=c.case_id) AND c.case_id<>?")) {
            statement.setObject(1, review.contract()); statement.setObject(2, proposal.id());
            try (ResultSet rows = statement.executeQuery()) { if (rows.next()) throw new IllegalArgumentException("Hợp đồng còn hồ sơ chưa được quyết định: " + rows.getObject(1, UUID.class)); }
        }
        String assets = assets(connection, evidence.job(), proposal.id());
        String findings = evidence.journal().stream().filter(row -> !row.proof() && (row.entry().state() == BlockJournal.State.APPLIED || row.entry().state() == BlockJournal.State.REVIEWED_UNCONFIRMED))
                .map(row -> "event=" + row.entry().id() + " state=" + row.entry().state() + " position=" + row.entry().position()
                        + " audit_id=" + row.auditSequence() + " audit_receipt_or_resolution_proof=UNVERIFIABLE").collect(Collectors.joining("\n"));
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO unrecoverable_cases(case_id,contract_id,review_id,proposer_id,plan,compensation,reason,evidence,world_observation,findings,assets) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(case_id) DO NOTHING")) {
            statement.setObject(1, proposal.id()); statement.setObject(2, review.contract()); statement.setObject(3, proposal.review()); statement.setObject(4, proposal.actor());
            statement.setString(5, proposal.plan().name()); statement.setLong(6, proposal.compensation()); statement.setString(7, proposal.reason()); statement.setString(8, evidence.canonical());
            statement.setString(9, review.world()); statement.setString(10, findings); statement.setString(11, assets); statement.executeUpdate();
        }
        UnrecoverableResolution.Case saved = incident(connection, proposal.actor(), proposal.id());
        if (!saved.proposer().equals(proposal.actor()) || !saved.review().equals(proposal.review()) || saved.plan() != proposal.plan()
                || saved.compensation() != proposal.compensation() || !saved.reason().equals(proposal.reason()) || !saved.assets().equals(assets) || !saved.evidence().equals(evidence.canonical()))
            throw new IllegalArgumentException("Case ID đã dùng với nội dung khác: " + proposal.id());
        return "Hồ sơ " + proposal.id() + " / " + proposal.plan() + "; bồi thường " + proposal.compensation() + " LT từ escrow. Chưa chuyển tài sản; cần người khác phê duyệt, fence giữ nguyên.";
    }

    static UnrecoverableResolution.Case incident(Connection connection, UUID actor, UUID id) throws SQLException {
        RecoveryStore.requireAuthority(connection, actor);
        try (PreparedStatement statement = connection.prepareStatement("SELECT contract_id,proposer_id,review_id,plan,compensation,reason,evidence,world_observation,assets,created_at>clock_timestamp()-interval '10 minutes' FROM unrecoverable_cases WHERE case_id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Không có hồ sơ UNRECOVERABLE: " + id);
                return new UnrecoverableResolution.Case(id, rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getObject(3, UUID.class),
                        UnrecoverableResolution.Plan.valueOf(rows.getString(4)), rows.getLong(5), rows.getString(6), rows.getString(7), rows.getString(8), rows.getString(9), rows.getBoolean(10));
            }
        }
    }

    static boolean committed(Connection connection, UUID actor, UUID id, UnrecoverableResolution.Outcome outcome, String reason) throws SQLException {
        RecoveryResolution.requireReason(reason);
        UnrecoverableResolution.Case incident = incident(connection, actor, id);
        GameDatabase.lockContractRecord(connection, incident.contract());
        if (actor.equals(incident.proposer())) throw new IllegalArgumentException("Người lập hồ sơ không được tự phê duyệt hoặc từ chối hồ sơ của mình.");
        try (PreparedStatement statement = connection.prepareStatement("SELECT actor_id,outcome,reason FROM unrecoverable_decisions WHERE case_id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return false;
                if (!actor.equals(rows.getObject(1, UUID.class)) || !outcome.name().equals(rows.getString(2)) || !reason.equals(rows.getString(3)))
                    throw new IllegalArgumentException("Hồ sơ đã có quyết định khác; không sửa lịch sử hoặc chuyển tài sản lần nữa: " + id);
                return true;
            }
        }
    }

    static Contracts.Job requireApproval(Connection connection, UUID actor, UUID id, List<RecoveryResolution.Observation> world) throws SQLException {
        UnrecoverableResolution.Case incident = incident(connection, actor, id);
        RecoveryStore.Stored review = RecoveryStore.stored(connection, incident.proposer(), incident.review());
        RecoveryResolution.Evidence current = RecoveryStore.evidence(connection, incident.proposer(), incident.contract());
        lockParticipants(connection, current.job());
        RecoveryResolution.observedCells(current, world);
        requireUnrecoverable(current);
        if (!incident.fresh() || !review.fresh() || !incident.evidence().equals(current.canonical())
                || !incident.assets().equals(assets(connection, current.job(), id)) || !incident.world().equals(RecoveryResolution.worldText(world)))
            throw new IllegalArgumentException("Hồ sơ/world/tài sản/quyền đã thay đổi hoặc hết hạn; từ chối hồ sơ rồi lập review mới.");
        if (incident.plan() == UnrecoverableResolution.Plan.SETTLE_HELD_ESCROW) {
            if (actor.equals(current.job().owner()) || current.job().builder().filter(actor::equals).isPresent())
                throw new IllegalArgumentException("Người phê duyệt chuyển tài sản phải độc lập với chủ hợp đồng và Builder.");
            requireFundedSettlement(connection, current.job(), incident.compensation());
        }
        return current.job();
    }

    private static void requireUnrecoverable(RecoveryResolution.Evidence evidence) {
        if (evidence.journal().stream().noneMatch(row -> !row.proof() && (row.entry().state() == BlockJournal.State.APPLIED || row.entry().state() == BlockJournal.State.REVIEWED_UNCONFIRMED)))
            throw new IllegalArgumentException("Không có proof APPLIED/quyết định quản trị không thể xác minh; không dùng quy trình UNRECOVERABLE thay resolve.");
    }

    static void lockParticipants(Connection connection, Contracts.Job job) throws SQLException {
        List<UUID> players = java.util.stream.Stream.concat(java.util.stream.Stream.of(job.owner()), job.builder().stream()).distinct().sorted().toList();
        for (UUID player : players) GameDatabase.lockPlayer(connection, player);
    }

    /** Chỉ phân bổ khoản ký quỹ còn nguyên và có đối ứng/receipt; mất proof tài chính phải giữ cách ly. */
    private static void requireFundedSettlement(Connection connection, Contracts.Job job, long compensation) throws SQLException {
        if (!Contracts.unresolved(job) || job.escrow() != job.reward() || job.reward() <= 0 || compensation > job.escrow()
                || compensation > 0 && (job.builder().isEmpty() || job.fundingSect().isEmpty() && job.builder().filter(job.owner()::equals).isPresent()))
            throw new IllegalArgumentException("Không có escrow còn nguyên để phân bổ hoặc Builder không hợp lệ: " + job.id());
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM contract_escrow_ledger e JOIN operations o USING(request_id) WHERE e.contract_id=? AND e.kind='DEPOSIT' AND e.delta=? AND o.result<>'pending' AND EXISTS(SELECT 1 FROM ledger l WHERE l.request_id=e.request_id AND l.delta=-e.delta AND (l.player_id=? OR l.sect_id=?)) AND NOT EXISTS(SELECT 1 FROM contract_escrow_ledger s WHERE s.contract_id=e.contract_id AND s.kind<>'DEPOSIT')")) {
            statement.setObject(1, job.id()); statement.setLong(2, job.reward()); statement.setObject(3, job.fundingSect().isEmpty() ? job.owner() : null); statement.setObject(4, job.fundingSect().orElse(null));
            try (ResultSet rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 1) throw new IllegalArgumentException("Escrow thiếu receipt/đối ứng hoặc đã thanh toán; không suy diễn số tiền cần hoàn: " + job.id()); }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT (SELECT count(*) FROM profiles p WHERE p.player_id IN (?,?) AND p.balance<>coalesce((SELECT sum(delta) FROM ledger WHERE player_id=p.player_id),0))+(SELECT count(*) FROM sect_accounts a WHERE a.sect_id=? AND a.balance<>coalesce((SELECT sum(delta) FROM ledger WHERE sect_id=a.sect_id),0))")) {
            statement.setObject(1, job.owner()); statement.setObject(2, job.builder().orElse(null)); statement.setObject(3, job.fundingSect().orElse(null));
            try (ResultSet rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalArgumentException("Số dư ví/ngân khố không khớp Ledger; không phân bổ escrow: " + job.id()); }
        }
        if (job.projectStage().isPresent()) {
            Projects.Stage stage = Projects.lockStage(connection, job.projectStage().orElseThrow());
            if (!stage.status().equals("RESERVED") || stage.costs().isEmpty() || !stage.contract().equals(java.util.Optional.of(job.id())) || !stage.reserved().equals(stage.costs()))
                throw new IllegalArgumentException("Dự trữ không khớp; không tự hoàn tài nguyên: " + stage.id());
            try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM (SELECT coalesce(i.resource_id,l.resource_id) FROM (SELECT resource_id,quantity FROM stage_inventory WHERE stage_id=?) i FULL JOIN (SELECT resource_id,sum(delta) quantity FROM resource_ledger WHERE stage_id=? GROUP BY resource_id) l USING(resource_id) WHERE coalesce(i.quantity,0)<>coalesce(l.quantity,0)) bad")) {
                statement.setObject(1, stage.id()); statement.setObject(2, stage.id());
                try (ResultSet rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalArgumentException("Ledger tài nguyên/dự trữ lệch; giữ cách ly: " + stage.id()); }
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM (SELECT coalesce(i.resource_id,l.resource_id) FROM (SELECT resource_id,quantity FROM project_inventory WHERE project_id=?) i FULL JOIN (SELECT resource_id,sum(delta) quantity FROM resource_ledger WHERE project_id=? GROUP BY resource_id) l USING(resource_id) WHERE coalesce(i.quantity,0)<>coalesce(l.quantity,0)) bad")) {
                statement.setObject(1, stage.project()); statement.setObject(2, stage.project());
                try (ResultSet rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalArgumentException("Ledger tài nguyên/kho dự án lệch; không hoàn dự trữ: " + stage.project()); }
            }
        }
    }

    static String assets(Connection connection, Contracts.Job job, UUID excludedCase) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT jsonb_build_object(
                  'contract',to_jsonb(c),
                  'funding', CASE WHEN c.funding_sect_id IS NULL THEN (SELECT to_jsonb(p) FROM profiles p WHERE p.player_id=c.owner_id) ELSE (SELECT to_jsonb(a) FROM sect_accounts a WHERE a.sect_id=c.funding_sect_id) END,
                  'builder',(SELECT to_jsonb(p) FROM profiles p WHERE p.player_id=c.builder_id),
                  'wallet_ledger',(SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.entry_id),'[]') FROM ledger l WHERE l.player_id IN (c.owner_id,c.builder_id) OR l.sect_id=c.funding_sect_id),
                  'escrow',(SELECT to_jsonb(e) FROM contract_escrow_accounts e WHERE e.contract_id=c.contract_id),
                  'escrow_ledger',(SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.entry_id),'[]') FROM contract_escrow_ledger e WHERE e.contract_id=c.contract_id),
                  'stage',(SELECT to_jsonb(s) FROM project_stages s WHERE s.stage_id=c.project_stage_id),
                  'reserve',(SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.resource_id),'[]') FROM stage_inventory i WHERE i.stage_id=c.project_stage_id),
                  'project_stock',(SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.resource_id),'[]') FROM project_inventory i WHERE i.project_id=(SELECT project_id FROM project_stages WHERE stage_id=c.project_stage_id)),
                  'resource_ledger',(SELECT coalesce(jsonb_agg(to_jsonb(l) ORDER BY l.entry_id),'[]') FROM resource_ledger l WHERE l.stage_id=c.project_stage_id OR l.project_id=(SELECT project_id FROM project_stages WHERE stage_id=c.project_stage_id)),
                  'player_stock',(SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.player_id,i.resource_id),'[]') FROM inventory i WHERE i.player_id IN(c.owner_id,c.builder_id)),
                  'tokens',(SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY t.token_id),'[]') FROM item_tokens t WHERE t.player_id IN(c.owner_id,c.builder_id)),
                  'cases',(SELECT coalesce(jsonb_agg(to_jsonb(i) ORDER BY i.case_id),'[]') FROM unrecoverable_cases i WHERE i.contract_id=c.contract_id AND i.case_id<>?),
                  'decisions',(SELECT coalesce(jsonb_agg(to_jsonb(d) ORDER BY d.case_id),'[]') FROM unrecoverable_decisions d JOIN unrecoverable_cases i USING(case_id) WHERE i.contract_id=c.contract_id AND i.case_id<>?)
                )::text FROM building_contracts c WHERE c.contract_id=?
                """)) {
            statement.setObject(1, excludedCase); statement.setObject(2, excludedCase); statement.setObject(3, job.id());
            try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("Không chụp được tài sản hợp đồng: " + job.id(), "55000"); return rows.getString(1); }
        }
    }

    static String decide(Connection connection, UUID actor, UUID id, UnrecoverableResolution.Outcome outcome, String reason, List<RecoveryResolution.Observation> world, Contracts.Job job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO unrecoverable_decisions(case_id,actor_id,outcome,reason,checked_world,assets_after,request_id,authority_revision) SELECT ?,?,?,?,?,?,?,revision FROM recovery_authorities WHERE player_id=? AND enabled")) {
            statement.setObject(1, id); statement.setObject(2, actor); statement.setString(3, outcome.name()); statement.setString(4, reason);
            statement.setString(5, RecoveryResolution.worldText(world)); statement.setString(6, assets(connection, job, id));
            statement.setObject(7, outcome == UnrecoverableResolution.Outcome.APPROVED ? UnrecoverableResolution.request(id) : null); statement.setObject(8, actor);
            if (statement.executeUpdate()!=1) throw new SQLException("Không ghi được quyết định với quyền hiện tại: case=" + id + " actor=" + actor, "55000");
        }
        return "Đã ghi quyết định " + outcome + " cho hồ sơ " + id + ". World/journal/fence giữ nguyên; đây không phải nghiệm thu công trình.";
    }

    static String report(Connection connection, UUID actor, UUID id) throws SQLException {
        incident(connection, actor, id);
        try (PreparedStatement statement = connection.prepareStatement("SELECT row_to_json(c)::text,(SELECT row_to_json(d)::text FROM unrecoverable_decisions d WHERE d.case_id=c.case_id) FROM unrecoverable_cases c WHERE c.case_id=?")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1) + "\nDECISION=" + rows.getString(2); }
        }
    }
}
