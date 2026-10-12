package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Connector đối soát trước READY; khóa hợp đồng nghi vấn, không sửa world hoặc dựng lịch sử. */
public final class ContractRecovery {
    private record Inspection(List<String> rows, List<BlockJournal.Finding> findings) { }
    private final JavaPlugin plugin;
    private final World world;

    public ContractRecovery(JavaPlugin plugin, World world) { this.plugin = plugin; this.world = world; }

    /** Chạy trên worker I/O; mọi truy cập block được chuyển sang region scheduler. */
    public void inspect(Contracts.Board board, List<GameDatabase.BlockAudit> audit, List<BlockJournal.Recorded> journal, GameDatabase database) throws IOException, SQLException {
        List<CompletableFuture<Inspection>> inspections = new ArrayList<>();
        for (Contracts.Job job : board.jobs()) {
            CompletableFuture<Inspection> inspection = new CompletableFuture<>();
            inspections.add(inspection);
            Bukkit.getRegionScheduler().execute(plugin, new Location(world, job.centerX(), 100, job.template().centerZ()), () -> {
                try {
                    List<String> rows = new ArrayList<>();
                    List<BlockJournal.Finding> findings = new ArrayList<>();
                    boolean superseded = board.jobs().subList(board.jobs().indexOf(job) + 1, board.jobs().size()).stream()
                            .anyMatch(later -> later.slot() == job.slot() && later.worksite() == job.worksite());
                    for (BuildingTemplates.Cell cell : job.template().cells(job.slot())) {
                        String position = cell.x() + ":" + cell.y() + ":" + cell.z();
                        String actual = BlockJournalWorld.material(world.getBlockAt(cell.x(), cell.y(), cell.z()));
                        GameDatabase.BlockAudit last = audit.stream().filter(row -> row.contract().equals(job.id()) && row.position().equals(position)).findFirst().orElse(null);
                        String expected = last == null ? "UNKNOWN" : last.event().equals("PLACE") ? cell.material() : "AIR";
                        String assessment = superseded ? "SUPERSEDED_SITE" : last == null ? "UNKNOWN_HISTORY" : expected.equals(actual) ? "MATCH_OBSERVED_STATE" : "WORLD_AUDIT_MISMATCH";
                        if (assessment.equals("WORLD_AUDIT_MISMATCH")) findings.add(new BlockJournal.Finding(job.id(), BlockJournal.Classification.MISMATCH, "audit/world " + position));
                        rows.add(job.id() + "\t" + job.status() + "\t" + position + "\t" + actual + "\t" + expected + "\t"
                                + (last == null ? "NONE" : Long.toString(last.sequence())) + "\t" + assessment);
                    }
                    for (BlockJournal.Recorded recorded : journal.stream().filter(row -> row.entry().contract().equals(job.id())).toList()) {
                        BlockJournal.Entry entry = recorded.entry();
                        BlockJournal.Classification classification = entry.state() == BlockJournal.State.INTENT ? BlockJournal.Classification.INCOMPLETE
                                : (entry.state() == BlockJournal.State.APPLIED || entry.state() == BlockJournal.State.REVIEWED_UNCONFIRMED) && !recorded.proof() ? BlockJournal.Classification.UNRECOVERABLE : BlockJournal.Classification.CONFIRMED;
                        boolean latest = journal.stream().noneMatch(other -> other.entry().contract().equals(job.id())
                                && other.entry().position().equals(entry.position()) && other.entry().state() == BlockJournal.State.APPLIED && other.auditSequence() > recorded.auditSequence());
                        if (!superseded && classification == BlockJournal.Classification.CONFIRMED && entry.state() == BlockJournal.State.APPLIED && latest) {
                            String[] coordinates = entry.position().split(":", -1);
                            String actual = BlockJournalWorld.material(world.getBlockAt(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])));
                            if (!actual.equals(entry.after())) classification = BlockJournal.Classification.MISMATCH;
                        }
                        String assessment = entry.state() == BlockJournal.State.REVIEWED_UNCONFIRMED && recorded.proof() ? "MANUAL_DECISION_NOT_EVENT_PROOF" : classification.name();
                        rows.add("# journal\t" + entry.id() + "\t" + entry.state() + "\t" + assessment + "\t" + recorded.auditSequence());
                        if (classification != BlockJournal.Classification.CONFIRMED) findings.add(new BlockJournal.Finding(job.id(), classification, "journal event=" + entry.id()));
                    }
                    inspection.complete(new Inspection(List.copyOf(rows), List.copyOf(findings)));
                } catch (RuntimeException failure) { inspection.completeExceptionally(failure); }
            });
        }
        try {
            CompletableFuture.allOf(inspections.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IOException("Đối soát world bị ngắt trước READY", failure);
        } catch (ExecutionException | TimeoutException failure) { throw new IOException("Không hoàn tất đối soát world trong 30 giây trước READY", failure); }
        List<String> rows = inspections.stream().flatMap(future -> future.join().rows().stream()).toList();
        for (BlockJournal.Finding finding : inspections.stream().flatMap(future -> future.join().findings().stream()).toList()) database.requireRecovery(finding);
        Path directory = plugin.getDataFolder().toPath().resolve("recovery");
        Files.createDirectories(directory);
        Path report = directory.resolve(UUID.randomUUID() + ".tsv");
        String content = "# observed_at=" + Instant.now() + "\n# Chỉ khớp trạng thái cuối; không chứng minh lịch sử đầy đủ hoặc phục hồi sự kiện đã mất.\n"
                + "contract\tstatus\tposition\tworld\taudit_expected\tevent_id\tassessment\n" + String.join("\n", rows) + "\n";
        try (FileChannel channel = FileChannel.open(report, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        long mismatch = rows.stream().filter(row -> row.endsWith("WORLD_AUDIT_MISMATCH")).count();
        long unknown = rows.stream().filter(row -> row.endsWith("UNKNOWN_HISTORY")).count();
        plugin.getSLF4JLogger().atWarn().addKeyValue("report", report).addKeyValue("mismatches", mismatch)
                .addKeyValue("unknown", unknown).addKeyValue("cells", rows.size()).log("contract_recovery_observation_requires_review_not_history_repair");
    }
}
