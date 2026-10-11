package vn.cuuvuc.core;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Connector hai nhịp: hủy callback đầu để chuẩn bị bền vững, chỉ cho phép lần thử lại phù hợp. */
public final class BlockJournalWorld {
    private enum Phase { PREPARING, READY, CONSUMED, ABORTING }
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
        if (entry == null) return;
        if (cancelled) { abort(entry); return; }
        Block block = callback.getBlock();
        Bukkit.getRegionScheduler().runDelayed(plugin, block.getLocation(), task -> {
            String actual = material(block);
            if (!entry.after().equals(actual)) { fail(entry, new IllegalStateException("Tick sau callback khác vật liệu: event=" + entry.id() + " expected=" + entry.after() + " actual=" + actual)); return; }
            Bukkit.getAsyncScheduler().runNow(plugin, work -> {
                try { database.applyBlock(entry); tickets.remove(entry.contract()); }
                catch (SQLException | IllegalArgumentException failure) { fail(entry, failure); }
            });
        }, 1L);
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
        contracts.holdRecovery(entry.contract());
        plugin.getSLF4JLogger().atError().addKeyValue("contract", entry.contract()).addKeyValue("event", entry.id()).setCause(failure).log("block_journal_failed_recovery_required");
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try { database.requireRecovery(new BlockJournal.Finding(entry.contract(), BlockJournal.Classification.INCOMPLETE, "callback event=" + entry.id())); }
            catch (SQLException persistence) { plugin.getSLF4JLogger().atError().addKeyValue("contract", entry.contract()).setCause(persistence).log("recovery_fence_persistence_failed_intent_remains_unresolved"); }
        });
    }

    static String position(Block block) { return block.getX() + ":" + block.getY() + ":" + block.getZ(); }
    static String material(Block block) { return block.getType().isAir() ? "AIR" : block.getType().name(); }
}
