package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Connector quản trị: world chỉ đọc trên region; báo cáo và DB chạy trên worker. */
public final class RecoveryCommands {
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final PlayerTasks tasks;
    private final ContractWorld contracts;
    private final World world;

    public RecoveryCommands(JavaPlugin plugin, GameDatabase database, PlayerTasks tasks, ContractWorld contracts, World world) {
        this.plugin = plugin; this.database = database; this.tasks = tasks; this.contracts = contracts; this.world = world;
    }

    public void command(Player player, String[] arguments) {
        if (!player.hasPermission("cuuvuc.recovery")) throw new IllegalArgumentException("Cần quyền cuuvuc.recovery và quyền phục hồi PostgreSQL.");
        if (arguments.length < 3) throw new IllegalArgumentException("/cv recovery review <contract> | show <review> | hold/resolve <review> <lý do> | incident-hold/incident-settle <review> | case/approve-case/reject-case <case>");
        UUID actor = player.getUniqueId(); UUID reference = UUID.fromString(arguments[2]);
        switch (arguments[1]) {
            case "review" -> {
                if (arguments.length != 3) throw new IllegalArgumentException("/cv recovery review <contract UUID>");
                tasks.submit(player, UUID.randomUUID(), () -> review(actor, reference));
            }
            case "show" -> {
                if (arguments.length != 3) throw new IllegalArgumentException("/cv recovery show <review UUID>");
                tasks.submit(player, UUID.randomUUID(), () -> export(actor, reference));
            }
            case "hold" -> {
                String reason = reason(arguments);
                tasks.submit(player, UUID.randomUUID(), () -> database.decideRecovery(actor, reference, RecoveryResolution.Decision.KEEP_LOCKED, reason, List.of()));
            }
            case "resolve" -> {
                String reason = reason(arguments);
                tasks.submit(player, UUID.randomUUID(), () -> resolve(actor, reference, reason));
            }
            case "incident-hold" -> {
                String reason = reason(arguments);
                tasks.submit(player, UUID.randomUUID(), () -> proposeIncident(actor, reference, UnrecoverableResolution.Plan.KEEP_QUARANTINED, 0, reason));
            }
            case "incident-settle" -> {
                if (arguments.length < 5) throw new IllegalArgumentException("/cv recovery incident-settle <review UUID> <bồi thường LT từ escrow, 0..1000> <lý do>");
                long compensation = Long.parseLong(arguments[3]);
                String reason = reasonFrom(arguments, 4);
                tasks.submit(player, UUID.randomUUID(), () -> proposeIncident(actor, reference, UnrecoverableResolution.Plan.SETTLE_HELD_ESCROW, compensation, reason));
            }
            case "case" -> {
                if (arguments.length != 3) throw new IllegalArgumentException("/cv recovery case <case UUID>");
                tasks.submit(player, UUID.randomUUID(), () -> exportIncident(actor, reference));
            }
            case "approve-case" -> {
                String reason = reason(arguments);
                tasks.submit(player, UUID.randomUUID(), () -> approveIncident(actor, reference, reason));
            }
            case "reject-case" -> {
                String reason = reason(arguments);
                tasks.submit(player, UUID.randomUUID(), () -> rejectIncident(actor, reference, reason));
            }
            default -> throw new IllegalArgumentException("Thao tác phục hồi không hợp lệ: " + arguments[1]);
        }
    }

    private static String reason(String[] arguments) {
        return reasonFrom(arguments, 3);
    }

    private static String reasonFrom(String[] arguments, int first) {
        String reason = String.join(" ", Arrays.copyOfRange(arguments, first, arguments.length));
        RecoveryResolution.requireReason(reason); return reason;
    }

    private String proposeIncident(UUID actor, UUID review, UnrecoverableResolution.Plan plan, long compensation, String reason) throws SQLException {
        UUID incident = UUID.randomUUID();
        String result = database.proposeUnrecoverable(new UnrecoverableResolution.Proposal(incident, actor, review, plan, compensation, reason));
        return result + " " + exportIncident(actor, incident);
    }

    private String approveIncident(UUID actor, UUID incident, String reason) throws SQLException {
        UnrecoverableResolution.Case data = database.unrecoverableCase(actor, incident);
        contracts.requireRecoveryQuiescent(data.contract());
        Contracts.Job job = database.contractBoard().jobs().stream().filter(value -> value.id().equals(data.contract())).findFirst().orElseThrow();
        String result = database.approveUnrecoverable(actor, incident, reason, observe(job));
        contracts.publish(database.contractBoard());
        return result + " " + exportIncident(actor, incident);
    }

    private String rejectIncident(UUID actor, UUID incident, String reason) throws SQLException {
        UnrecoverableResolution.Case data = database.unrecoverableCase(actor, incident);
        contracts.requireRecoveryQuiescent(data.contract());
        Contracts.Job job = database.contractBoard().jobs().stream().filter(value -> value.id().equals(data.contract())).findFirst().orElseThrow();
        return database.rejectUnrecoverable(actor, incident, reason, observe(job)) + " " + exportIncident(actor, incident);
    }

    private String review(UUID actor, UUID contract) throws SQLException {
        RecoveryResolution.Evidence evidence = database.recoveryEvidence(actor, contract);
        contracts.requireRecoveryQuiescent(contract);
        List<RecoveryResolution.Observation> cells = observe(evidence.job());
        UUID review = UUID.randomUUID();
        String result = database.createRecoveryReview(new RecoveryResolution.Review(review, actor, evidence, cells));
        return result + " " + export(actor, review);
    }

    private String resolve(UUID actor, UUID review, String reason) throws SQLException {
        Optional<String> committed = database.recoveryDecision(actor, review, RecoveryResolution.Decision.RESOLVE_INTENTS_AND_REOPEN, reason);
        if (committed.isPresent()) return committed.orElseThrow();
        RecoveryResolution.Evidence evidence = database.reviewedRecoveryEvidence(actor, review);
        UUID contract = evidence.job().id();
        contracts.requireRecoveryQuiescent(contract);
        List<RecoveryResolution.Observation> cells = observe(evidence.job());
        String result = database.decideRecovery(actor, review, RecoveryResolution.Decision.RESOLVE_INTENTS_AND_REOPEN, reason, cells);
        contracts.releaseRecovery(contract);
        return result;
    }

    private List<RecoveryResolution.Observation> observe(Contracts.Job job) throws SQLException {
        CompletableFuture<List<RecoveryResolution.Observation>> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, new Location(world, job.centerX(), 100, job.template().centerZ()), () -> {
            try {
                result.complete(job.template().cells(job.slot()).stream().map(cell -> new RecoveryResolution.Observation(
                        cell.x() + ":" + cell.y() + ":" + cell.z(), BlockJournalWorld.material(world.getBlockAt(cell.x(), cell.y(), cell.z())))).toList());
            } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        });
        try { return result.get(10, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException("Quan sát phục hồi bị ngắt; giữ fence: " + job.id(), "57014", failure); }
        catch (ExecutionException | TimeoutException failure) { throw new SQLException("Không đọc được world trong 10 giây; giữ fence: " + job.id(), "57014", failure); }
    }

    private String export(UUID actor, UUID review) throws SQLException {
        String report = database.recoveryReport(actor, review);
        return writeReport(actor, review, report, plugin.getDataFolder().toPath().resolve("recovery/reviews"));
    }

    private String exportIncident(UUID actor, UUID incident) throws SQLException {
        return writeReport(actor, incident, database.unrecoverableReport(actor, incident), plugin.getDataFolder().toPath().resolve("recovery/cases"));
    }

    private String writeReport(UUID actor, UUID reference, String report, Path directory) throws SQLException {
        Path path = directory.resolve(reference + "-" + UUID.randomUUID() + ".txt");
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(report + "\n");
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (IOException failure) { throw new SQLException("Hồ sơ vẫn lưu trong DB; không ghi được file tại " + path + ". Dùng show/case để xuất lại.", "58030", failure); }
        plugin.getSLF4JLogger().atInfo().addKeyValue("actor", actor).addKeyValue("reference", reference).addKeyValue("report", path).log("recovery_evidence_exported");
        return "Hồ sơ: " + path;
    }
}
