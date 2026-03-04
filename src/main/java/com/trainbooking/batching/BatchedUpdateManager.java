package com.trainbooking.batching;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SOLUTION: Batched ZooKeeper State Update Manager
 * ═══════════════════════════════════════════════════════════════════════
 *
 * PROBLEM:
 * Apache Helix uses ZooKeeper as its coordination backbone.
 * In a busy booking system, each seat booking event may trigger a
 * cluster-state write to ZooKeeper. ZooKeeper processes writes
 * sequentially, so N bookings → N sequential writes → bottleneck.
 *
 * SOLUTION — State Update Batching:
 * Instead of writing every event immediately, we buffer them in a
 * local ConcurrentLinkedQueue. A background thread drains the queue
 * every BATCH_INTERVAL_MS milliseconds (or whenever the queue reaches
 * BATCH_SIZE items) and issues a SINGLE simulated ZooKeeper write
 * per batch.
 *
 * Booking events
 * ↓
 * Local update queue (in-memory, sub-microsecond enqueue)
 * ↓
 * Batch every 2 s OR every BATCH_SIZE items
 * ↓
 * Single ZooKeeper write per batch
 *
 * ALGORITHM (Pseudo-code):
 * loop every BATCH_INTERVAL_MS:
 * drain all events from queue → batch
 * if batch not empty:
 * zkWrite( batch ) // ONE operation
 * zkWriteCount++
 *
 * MEASURABLE IMPROVEMENT:
 * Without batching : 100 bookings → ~100 ZooKeeper writes
 * With batching : 100 bookings → ceil(100/BATCH_SIZE) writes
 */
public class BatchedUpdateManager {

    private static final Logger log = LoggerFactory.getLogger(BatchedUpdateManager.class);

    // ── Configuration ──────────────────────────────────────────────
    private final int batchSize; // flush when queue >= batchSize
    private final long batchIntervalMs; // flush every N ms regardless
    private final boolean batchingEnabled;

    // ── Internal State ─────────────────────────────────────────────
    private final ConcurrentLinkedQueue<String> eventQueue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;

    // ── Metrics ────────────────────────────────────────────────────
    private final AtomicLong totalEventsEnqueued = new AtomicLong(0);
    private final AtomicLong zkWritesWithBatching = new AtomicLong(0);
    private final AtomicLong zkWritesWithoutBatching = new AtomicLong(0);

    public BatchedUpdateManager(boolean batchingEnabled, int batchSize, long batchIntervalMs) {
        this.batchingEnabled = batchingEnabled;
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;

        if (batchingEnabled) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BatchFlushThread");
                t.setDaemon(true);
                return t;
            });
            // Schedule flush task at fixed rate
            scheduler.scheduleAtFixedRate(this::flushBatch,
                    batchIntervalMs, batchIntervalMs, TimeUnit.MILLISECONDS);
            log.info("[Batching ON]  batchSize={}, intervalMs={}", batchSize, batchIntervalMs);
        } else {
            scheduler = null;
            log.info("[Batching OFF] every booking event triggers a ZooKeeper write.");
        }
    }

    /**
     * Called for every booking operation.
     * In NO-BATCH mode → immediate ZooKeeper write.
     * In BATCH mode → enqueue for later flush.
     */
    public void recordBookingEvent(String event) {
        totalEventsEnqueued.incrementAndGet();

        if (!batchingEnabled) {
            // WITHOUT batching: write immediately → one write per event
            simulateZkWrite(List.of(event));
            zkWritesWithoutBatching.incrementAndGet();
        } else {
            // WITH batching: only enqueue
            eventQueue.offer(event);
            // If queue reached batchSize, flush immediately (size-triggered)
            if (eventQueue.size() >= batchSize) {
                flushBatch();
            }
        }
    }

    /**
     * Drains the queue and issues a SINGLE ZooKeeper write for the whole batch.
     */
    private synchronized void flushBatch() {
        if (eventQueue.isEmpty())
            return;

        List<String> batch = new ArrayList<>();
        String event;
        while ((event = eventQueue.poll()) != null) {
            batch.add(event);
        }
        if (!batch.isEmpty()) {
            simulateZkWrite(batch);
            zkWritesWithBatching.incrementAndGet();
            log.debug("[BatchFlush] {} events flushed in 1 ZK write.", batch.size());
        }
    }

    /**
     * Simulates a ZooKeeper write.
     * In a real Helix deployment this would update a ZNode via
     * HelixDataAccessor or ZkClient. Here we log the payload.
     */
    private void simulateZkWrite(List<String> events) {
        // Represent latency of a ZK write (~1-5 ms in a local cluster)
        try {
            Thread.sleep(2);
        } catch (InterruptedException ignored) {
        }
        log.debug("[ZK-WRITE] Batch of {} events: {}", events.size(), events);
    }

    /**
     * Force a final flush (call before shutdown).
     */
    public void flushAll() {
        if (batchingEnabled)
            flushBatch();
    }

    public void shutdown() {
        flushAll();
        if (scheduler != null)
            scheduler.shutdownNow();
    }

    // ── Metrics accessors ─────────────────────────────────────────

    public long getTotalEventsEnqueued() {
        return totalEventsEnqueued.get();
    }

    public long getZkWritesWithBatching() {
        return zkWritesWithBatching.get();
    }

    public long getZkWritesWithoutBatching() {
        return zkWritesWithoutBatching.get();
    }

    public boolean isBatchingEnabled() {
        return batchingEnabled;
    }

    /**
     * Prints a side-by-side performance comparison report.
     */
    public static void printComparisonReport(
            BatchedUpdateManager noBatch,
            BatchedUpdateManager withBatch) {

        long events = noBatch.getTotalEventsEnqueued();
        long noWrites = noBatch.getZkWritesWithoutBatching();
        long bWrites = withBatch.getZkWritesWithBatching();
        double reduction = 100.0 * (noWrites - bWrites) / noWrites;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║      ZooKeeper Write Comparison — Batching vs No-Batching   ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Booking events processed       : %-28d║%n", events);
        System.out.printf("║  ZooKeeper writes WITHOUT batch : %-28d║%n", noWrites);
        System.out.printf("║  ZooKeeper writes WITH batch    : %-28d║%n", bWrites);
        System.out.printf("║  Write reduction                : %-27.1f%%║%n", reduction);
        System.out.println("║                                                              ║");
        System.out.printf("║  ➜ Batching reduced ZK writes by %.1f%% (%d → %d)%s║%n",
                reduction, noWrites, bWrites,
                pad(String.format("%.1f%% (%d → %d)", reduction, noWrites, bWrites)));
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static String pad(String s) {
        int targetLineWidth = 29;
        int used = s.length();
        return " ".repeat(Math.max(0, targetLineWidth - used));
    }
}
