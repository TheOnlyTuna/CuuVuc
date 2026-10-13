package vn.cuuvuc.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Quyết định quản trị không phải bằng chứng callback; không tạo lịch sử PLACE/BREAK. */
public interface RecoveryResolution {
    enum Decision { KEEP_LOCKED, RESOLVE_INTENTS_AND_REOPEN }
    record Observation(String position, String material) { }
    record Evidence(Contracts.Job job, List<BlockJournal.Recorded> journal, List<GameDatabase.BlockAudit> audit, String canonical) {
        public Evidence { journal = List.copyOf(journal); audit = List.copyOf(audit); }
    }
    record Review(UUID id, UUID actor, Evidence evidence, List<Observation> world) {
        public Review { world = List.copyOf(world); }
    }

    static String worldText(List<Observation> world) {
        return world.stream().sorted(java.util.Comparator.comparing(Observation::position))
                .map(cell -> cell.position() + "=" + cell.material()).collect(Collectors.joining("\n"));
    }

    static void requireReason(String reason) {
        if (reason.length() < 8 || reason.length() > 500 || reason.isBlank() || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Lý do phải có 8..500 ký tự, không có ký tự điều khiển.");
    }

    /** Chỉ kết thúc ý định chưa chứng minh khi world còn trạng thái trước; giữ nguyên mọi proof đã commit. */
    static void requireReopenable(Evidence evidence, List<Observation> world) {
        Map<String, String> cells = observedCells(evidence, world);
        for (BlockJournal.Recorded row : evidence.journal()) {
            BlockJournal.Entry entry = row.entry();
            if ((entry.state() == BlockJournal.State.APPLIED || entry.state() == BlockJournal.State.REVIEWED_UNCONFIRMED) && !row.proof())
                throw new IllegalArgumentException("Thiếu audit/receipt; giữ khóa để xử lý thủ công: " + entry.id());
            if (entry.state() == BlockJournal.State.INTENT && !entry.before().equals(cells.get(entry.position())))
                throw new IllegalArgumentException("World khác trạng thái trước INTENT; không suy diễn sự kiện: " + entry.id());
        }
        for (GameDatabase.BlockAudit row : evidence.audit()) {
            String expected = row.event().equals("PLACE") ? evidence.job().template().material() : "AIR";
            if (!expected.equals(cells.get(row.position())))
                throw new IllegalArgumentException("World/audit còn lệch; giữ khóa tại " + row.position());
        }
        if (evidence.journal().isEmpty() && evidence.audit().isEmpty())
            throw new IllegalArgumentException("Không có bằng chứng journal/audit; cần quy trình thủ công, không tự mở fence.");
    }

    static Map<String, String> observedCells(Evidence evidence, List<Observation> world) {
        Map<String, String> cells = world.stream().collect(Collectors.toUnmodifiableMap(Observation::position, Observation::material));
        List<String> expected = evidence.job().template().cells(evidence.job().slot()).stream().map(cell -> cell.x() + ":" + cell.y() + ":" + cell.z()).toList();
        if (cells.size() != expected.size() || !cells.keySet().containsAll(expected)) throw new IllegalArgumentException("Quan sát world không đủ đúng các ô template.");
        return cells;
    }
}
