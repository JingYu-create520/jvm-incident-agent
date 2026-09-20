package dev.jingyu.jia.victim;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident 3: allocation-storm code that only bites under a capped heap.
 *
 * <p>Phase 1 loads a batch working set - hundreds of 0.7-3 MB arrays, about half of the capped
 * heap - which is promoted to old gen immediately. On a 256 MB G1 heap the region size is 1 MB,
 * so every one of those arrays is <em>humongous</em> (&gt; 512 KB) and is allocated directly into
 * contiguous old-gen regions.</p>
 *
 * <p>Phase 2 keeps the working set the same size but constantly replaces it in place (1 % of the
 * batch re-read per round) while churning short-lived {@code String}/{@code char[]} copies on top.
 * The live set never grows - the collector is simply never allowed to catch up: young pauses fire
 * every few tens of milliseconds, G1 runs back-to-back concurrent mark cycles, humongous
 * allocation fails to find contiguous regions, and once the heap is close enough to full the JVM
 * falls back to "Pause Full (G1 Humongous Allocation)" / "(G1 Compaction Pause)" with long pauses
 * that reclaim almost nothing. That is the GCA001/GCA002/GCA004 fingerprint.</p>
 *
 * <p>Unlike {@link LeakyCache} everything here is released when the batch finishes, so the
 * post-GC occupancy plateaus instead of climbing monotonically forever - the heap-leak fingerprint
 * (GCA003) must NOT fire on this capture. Pass {@code async=true} to trigger the batch on a
 * {@code victim-batch-} thread so the capture scripts can grab artifacts mid-storm.</p>
 */
@Component
public class GcStormService {

    private static final Logger log = LoggerFactory.getLogger(GcStormService.class);
    private static final int MB = 1024 * 1024;

    private final AtomicLong roundsCompleted = new AtomicLong();
    private final AtomicLong bytesChurned = new AtomicLong();
    private final AtomicLong batchId = new AtomicLong();
    private final AtomicLong droppedBatches = new AtomicLong();
    private final AtomicLong loadedBackoffs = new AtomicLong();
    private volatile boolean running;
    private volatile long sink;

    public Map<String, Object> stats() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roundsCompleted", roundsCompleted.get());
        body.put("churnedMb", bytesChurned.get() / MB);
        body.put("heapCeilingHits", droppedBatches.get());
        body.put("loadBackoffs", loadedBackoffs.get());
        body.put("running", running);
        return body;
    }

    /**
     * @param rounds      storm rounds
     * @param workingSetMb batch working set; 0 means "half the max heap"
     * @return what was done (or, when async, what was started)
     */
    public Map<String, Object> storm(int rounds, int workingSetMb) {
        long id = batchId.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                runBatch(id, rounds, workingSetMb);
            } catch (Throwable e) {
                log.warn("batch {} aborted", id, e);
            }
        }, "victim-batch-" + id);
        t.setDaemon(true);
        t.start();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", id);
        body.put("rounds", rounds);
        body.put("thread", t.getName());
        body.put("note", "storm runs on a background thread - capture artifacts while it is running");
        return body;
    }

    /** Synchronous variant: used by the health-check smoke tests and by callers that want to wait. */
    public Map<String, Object> stormBlocking(int rounds, int workingSetMb) throws InterruptedException {
        long id = batchId.incrementAndGet();
        Thread t = new Thread(() -> {
            try {
                runBatch(id, rounds, workingSetMb);
            } catch (Throwable e) {
                log.warn("batch {} aborted", id, e);
            }
        }, "victim-batch-" + id);
        t.setDaemon(true);
        t.start();
        t.join();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batch", id);
        body.put("rounds", rounds);
        body.put("thread", t.getName());
        return body;
    }

    private void runBatch(long id, int rounds, int workingSetMb) {
        running = true;
        long t0 = System.nanoTime();
        int targetMb = workingSetMb > 0 ? workingSetMb : (int) (maxHeap() / MB / 2);
        List<byte[]> batch = new ArrayList<>();
        Random rnd = new Random(20240117L);
        long localSink = 0;

        try {
            // ---- phase 1: load the batch, it lands in old gen (humongous) -----------------
            loadBatch(batch, targetMb, rnd);
            log.info("batch {} loaded working set {} MB across {} humongous arrays (heap max {} MB)",
                    id, retainedBytes(batch) / MB, batch.size(), maxHeap() / MB);

            // ---- phase 2: keep the size flat, keep replacing, never stop pressuring -------
            for (int r = 0; r < rounds; r++) {
                try {
                    int refresh = Math.max(2, batch.size() / 20);
                    for (int i = 0; i < refresh; i++) {
                        int slot = rnd.nextInt(batch.size());
                        byte[] old = batch.get(slot);
                        byte[] replacement = new byte[700_000 + rnd.nextInt(2_400_000)];
                        batch.set(slot, replacement);      // old one becomes garbage immediately
                        Arrays.fill(replacement, (byte) (r + i));
                        bytesChurned.addAndGet(replacement.length + old.length);
                    }
                    // short-lived young-gen garbage on top of the batch
                    for (int i = 0; i < 4; i++) {
                        byte[] scratch = new byte[64 * 1024 + rnd.nextInt(400 * 1024)];
                        Arrays.fill(scratch, (byte) (r + i));
                        String text = new String(scratch, 0, Math.min(scratch.length, 32 * 1024),
                                StandardCharsets.ISO_8859_1);
                        localSink += text.toUpperCase().length() + text.hashCode();
                        bytesChurned.addAndGet(scratch.length);
                    }
                } catch (OutOfMemoryError oome) {
                    // a batch that cannot get a new buffer trims its own working set and carries
                    // on - this is what keeps the storm going instead of killing the JVM at the
                    // first allocation failure near the heap ceiling
                    droppedBatches.incrementAndGet();
                    trim(batch, Math.max(1, batch.size() / 10));
                    log.warn("batch {} hit the heap ceiling at round {}, trimmed to {} MB", id, r,
                            retainedBytes(batch) / MB);
                }
                roundsCompleted.incrementAndGet();
                if (r % 10 == 9) {
                    log.info("batch {} round {} churned {} MB heapUsedMb {}", id, r + 1,
                            bytesChurned.get() / MB, usedHeap() / MB);
                }
                // a real batch job does per-record work; that pause is what lets the collector
                // fall behind and start to-space exhaustion / full collections
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (OutOfMemoryError oome) {
            log.warn("batch {} could not finish loading its working set", id, oome);
        } finally {
            batch.clear();                                  // <-- released properly, unlike a leak
            sink = localSink;
            running = false;
            log.info("batch {} finished in {} ms and released its working set", id,
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** Load the working set, backing off by 10 % whenever the capped heap refuses. */
    private void loadBatch(List<byte[]> batch, int targetMb, Random rnd) {
        int backoffs = 0;
        while (retainedBytes(batch) < (long) targetMb * MB) {
            try {
                batch.add(freshArray(rnd, rnd.nextLong()));
            } catch (OutOfMemoryError oome) {
                if (++backoffs > 20) {
                    // the capped heap simply cannot hold the requested batch: settle for what
                    // fits and run the storm on that, instead of spinning on allocation failures
                    log.warn("batch cannot grow past {} MB on a {} MB heap after {} backoffs",
                            retainedBytes(batch) / MB, maxHeap() / MB, backoffs);
                    return;
                }
                trim(batch, Math.max(1, batch.size() / 10));
                loadedBackoffs.incrementAndGet();
            }
        }
    }

    private static void trim(List<byte[]> batch, int n) {
        for (int i = 0; i < n && !batch.isEmpty(); i++) {
            batch.remove(batch.size() - 1);
        }
    }

    private byte[] freshArray(Random rnd, long seed) {
        // 0.7 MB .. 3 MB: always humongous on a 256 MB G1 heap
        byte[] buffer = new byte[700_000 + rnd.nextInt(2_400_000)];
        Arrays.fill(buffer, (byte) seed);
        return buffer;
    }

    private static long retainedBytes(List<byte[]> batch) {
        long total = 0;
        for (byte[] b : batch) {
            total += b.length;
        }
        return total;
    }

    private static long maxHeap() {
        return Math.max(64 * MB, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
