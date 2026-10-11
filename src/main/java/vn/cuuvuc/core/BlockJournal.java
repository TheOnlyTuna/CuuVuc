package vn.cuuvuc.core;

import java.util.UUID;

/** Ý định không phải sự kiện; APPLIED chỉ được ghi cùng audit sau callback và quan sát tick kế. */
public interface BlockJournal {
    enum State { INTENT, APPLIED, ABORTED }
    enum Classification { CONFIRMED, INCOMPLETE, MISMATCH, UNRECOVERABLE }
    record Entry(UUID id, UUID contract, UUID actor, String event, String position, String before, String after, State state) { }
    record Recorded(Entry entry, long auditSequence, boolean proof) { }
    record Finding(UUID contract, Classification classification, String reason) { }
}
