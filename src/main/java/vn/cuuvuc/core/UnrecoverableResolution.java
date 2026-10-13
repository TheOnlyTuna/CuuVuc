package vn.cuuvuc.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Phân bổ hành chính chỉ dùng escrow được chứng minh; không xác nhận thi công hoặc mở vùng cách ly. */
public interface UnrecoverableResolution {
    enum Plan { KEEP_QUARANTINED, SETTLE_HELD_ESCROW }
    enum Outcome { APPROVED, REJECTED }
    record Proposal(UUID id, UUID actor, UUID review, Plan plan, long compensation, String reason) { }
    record Case(UUID id, UUID contract, UUID proposer, UUID review, Plan plan, long compensation,
                String reason, String evidence, String world, String assets, boolean fresh) { }

    static UUID request(UUID incident) {
        return UUID.nameUUIDFromBytes(("cuuvuc:unrecoverable:approval:" + incident).getBytes(StandardCharsets.UTF_8));
    }

    static void requirePlan(Plan plan, long compensation) {
        if (compensation < 0 || compensation > 1000 || plan == Plan.KEEP_QUARANTINED && compensation != 0)
            throw new IllegalArgumentException("Bồi thường phải từ 0..1000 LT và chỉ được phân bổ trong SETTLE_HELD_ESCROW.");
    }
}
