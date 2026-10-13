package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Connector hai nhịp: hủy callback đầu để chuẩn bị bền vững, chỉ cho phép lần thử lại phù hợp. */
public final class BlockJournalWorld {
    private enum Phase { PREPARING, READY, CONSUMED, ABORTING, RECOVERY_REQUIRED }
    private record Ticket(BlockJournal.Entry entry, Phase phase, long observedAt) { }
    private final JavaPlugin plugin;
    private final GameDatabase database;
    private final ContractWorld contracts;
    private final ConcurrentHashMap<UUID, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockEvent, BlockJournal.Entry> accepted = new ConcurrentHashMap<>();

    public BlockJournalWorld(JavaPlugin plugin, GameDatabase database, ContractWorld contracts) {
        this.plugin = plugin; this.database = database; this.contracts = contracts;
    }

    public void start() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> expire(), 1, 1, TimeUnit.SECONDS);
    }

    public boolean busy(UUID contract) { return tickets.containsKey(contract); }

    public boolean authorize(BlockEvent callback, UUID actor, String event, String before, String after) {
        Contracts.Job job = contracts.at(callback.getBlock()).orElseThrow();
        String position = position(callback.getBlock());
        Ticket existing = tickets.get(job.id());
        if (existing != null) {
            BlockJournal.Entry entry = existing.entry();
            if (existing.phase() != Phase.READY || System.nanoTime() - existing.observedAt() > TimeUnit.SECONDS.toNanos(10) || !entry.actor().equals(actor) || !entry.event().equals(event)
                    || !entry.position().equals(position) || !entry.before().equals(before) || !entry.after().equals(after)) return false;
            Ticket consumed = new Ticket(entry, Phase.CONSUMED, existing.observedAt());
            if (!tickets.replace(job.id(), existing, consumed)) return false;
            accepted.put(callback, entry); return true;
        }
        BlockJournal.Entry entry = new BlockJournal.Entry(UUID.randomUUID(), job.id(), actor, event, position, before, after, BlockJournal.State.INTENT);
        Ticket preparing = new Ticket(entry, Phase.PREPARING, System.nanoTime());
        if (tickets.putIfAbsent(job.id(), preparing) != null) return false;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                database.prepareBlock(entry);
                tickets.replace(job.id(), preparing, new Ticket(entry, Phase.READY, System.nanoTime()));
            } catch (SQLException | IllegalArgumentException failure) { fail(entry, failure); }
        });
        return false;
    }

    /** MONITOR không bị hủy chưa đủ chứng minh: đọc block thật ở tick kế trước khi commit APPLIED. */
    public void observe(BlockEvent callback, boolean cancelled) {
        BlockJournal.Entry entry = accepted.remove(callback);
        if (entry == null) { if (cancelled) inspectRejectedIntent(callback); return; }
        if (cancelled) { abort(entry); return; }
        Block block = callback.getBlock();
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getLocation(), task -> {
            String actual = material(block);
            if (!entry.after().equals(actual)) { fail(entry, new IllegalStateException("Tick sau callback khác vật liệu: event=" + entry.id() + " expected=" + entry.after() + " actual=" + actual)); return; }
            Bukkit.getAsyncScheduler().runNow(plugin, work -> {
                try {
                    database.applyObservedBlock(entry, () -> observeMaterial(block));
                    Bukkit.getRegionScheduler().execute(plugin, block.getLocation(), () -> {
                        String committedWorld = material(block);
                        if (!entry.after().equals(committedWorld)) {
                            persistFailure(entry, new BlockJournal.Finding(entry.contract(), BlockJournal.Classification.MISMATCH, "world changed after commit event=" + entry.id()),
                                    new IllegalStateException("World sau commit khác vật liệu: expected=" + entry.after() + " actual=" + committedWorld));
                            return;
                        }
                        tickets.remove(entry.contract());
                    });
                }
                catch (SQLException | IllegalArgumentException failure) { fail(entry, failure); }
            });
        }, 1L);
    }

    /** Callback bị từ chối vẫn có thể để lại thay đổi trực tiếp của plugin khác trên ô có INTENT. */
    private void inspectRejectedIntent(BlockEvent callback) {
        contracts.at(callback.getBlock()).ifPresent(job -> {
            Ticket pending = tickets.get(job.id());
            if (pending == null || pending.phase() != Phase.READY && pending.phase() != Phase.PREPARING
                    || !pending.entry().position().equals(position(callback.getBlock()))) return;
            Bukkit.getRegionScheduler().runDelayed(plugin, callback.getBlock().getLocation(), task -> {
                Ticket current = tickets.get(job.id());
                if (current == null || current.phase() != Phase.READY && current.phase() != Phase.PREPARING
                        || !current.entry().id().equals(pending.entry().id())) return;
                String actual = material(callback.getBlock());
                if (!pending.entry().before().equals(actual)) fail(pending.entry(), new IllegalStateException(
                        "Callback bị từ chối nhưng world đã đổi: expected=" + pending.entry().before() + " actual=" + actual));
            }, 1L);
        });
    }

    /** Worker DB chỉ chờ quan sát có giới hạn; không đọc Bukkit world từ thread SQL. */
    private String observeMaterial(Block block) throws SQLException {
        CompletableFuture<String> observed = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, block.getLocation(), () -> {
            try { observed.complete(material(block)); }
            catch (IllegalStateException failure) { observed.completeExceptionally(failure); }
        });
        try { return observed.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException failure) { throw new SQLException("Hết hạn đọc world trước commit: position=" + position(block), "57014", failure); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new SQLException("Bị ngắt khi đọc world trước commit: position=" + position(block), "57014", failure);
        }
        catch (ExecutionException failure) { throw new SQLException("Không đọc được world trước commit: position=" + position(block), "55000", failure.getCause()); }
    }

    private void expire() {
        for (Ticket ticket : tickets.values()) {
            if (ticket.phase() == Phase.READY && System.nanoTime() - ticket.observedAt() > TimeUnit.SECONDS.toNanos(10)
                    && tickets.replace(ticket.entry().contract(), ticket, new Ticket(ticket.entry(), Phase.ABORTING, ticket.observedAt()))) abort(ticket.entry());
        }
    }

    private void abort(BlockJournal.Entry entry) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { database.abortBlock(entry.id()); tickets.remove(entry.contract()); }
            catch (SQLException | IllegalArgumentException failure) { fail(entry, failure); }
        });
    }

    private void fail(BlockJournal.Entry entry, Exception failure) {
        persistFailure(entry, new BlockJournal.Finding(entry.contract(), BlockJournal.Classification.INCOMPLETE, "callback event=" + entry.id()), failure);
    }

    private void persistFailure(BlockJournal.Entry entry, BlockJournal.Finding finding, Exception failure) {
        tickets.computeIfPresent(entry.contract(), (contract, ticket) -> ticket.entry().id().equals(entry.id())
                ? new Ticket(ticket.entry(), Phase.RECOVERY_REQUIRED, ticket.observedAt()) : ticket);
        contracts.holdRecovery(entry.contract());
        plugin.getSLF4JLogger().atError().addKeyValue("contract", entry.contract()).addKeyValue("event", entry.id()).setCause(failure).log("block_journal_failed_recovery_required");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { database.requireRecovery(finding); }
            catch (SQLException persistence) { plugin.getSLF4JLogger().atError().addKeyValue("contract", entry.contract()).setCause(persistence).log("recovery_fence_persistence_failed_intent_remains_unresolved"); }
        });
    }

    static String position(Block block) { return block.getX() + ":" + block.getY() + ":" + block.getZ(); }
    static String material(Block block) { return block.getType().isAir() ? "AIR" : block.getType().name(); }
}
