package vn.cuuvuc.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dữ liệu hợp đồng bất biến; quyền chỉ có hiệu lực trong vùng và thời hạn của hợp đồng. */
public interface Contracts {
    enum Status { OPEN, ACTIVE, SUBMITTED, COMPLETED, CANCELLED, EXPIRED }
    record Job(UUID id, UUID owner, Optional<UUID> builder, int slot, long reward, long escrow,
               Status status, Instant expiresAt, long revision, Optional<UUID> fundingSect) {
        public Job { builder = java.util.Objects.requireNonNull(builder); fundingSect = java.util.Objects.requireNonNull(fundingSect); }
        public int centerX() { return slot * 64 + 8; }
    }
    record Board(long revision, Instant databaseTime, List<Job> jobs) {
        public Board { jobs = List.copyOf(jobs); }
    }
    static UUID request(UUID contract, String action) {
        return UUID.nameUUIDFromBytes(("cuuvuc:contract:" + contract + ":" + action).getBytes(StandardCharsets.UTF_8));
    }
    static boolean unresolved(Job job) {
        return job.status() == Status.OPEN || job.status() == Status.ACTIVE || job.status() == Status.SUBMITTED;
    }
    static boolean contains(Job job, int x, int y, int z) {
        return x >= job.centerX() - 1 && x <= job.centerX() + 1 && y >= 100 && y <= 103 && z >= 7 && z <= 9;
    }
    static boolean builderCanBuild(Job job, UUID player, int x, int y, int z, Instant now) {
        return job.status() == Status.ACTIVE && job.builder().filter(player::equals).isPresent()
                && now.isBefore(job.expiresAt()) && contains(job, x, y, z);
    }
}
