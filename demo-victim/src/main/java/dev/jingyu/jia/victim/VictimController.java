package dev.jingyu.jia.victim;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per planted incident. Everything is GET so an operator can trigger an incident
 * with curl or a browser, and every response is JSON so the capture scripts can log what was
 * planted next to the artifacts.
 */
@RestController
@RequestMapping(value = "/victim", produces = MediaType.APPLICATION_JSON_VALUE)
public class VictimController {

    private static final Logger log = LoggerFactory.getLogger(VictimController.class);

    private final DeadlockPlant deadlockPlant;
    private final LeakyCache leakyCache;
    private final GcStormService gcStormService;
    private final ThreadLeakService threadLeakService;
    private final FlakyOrderService flakyOrderService;
    private final ResidentWorkers workers;

    public VictimController(DeadlockPlant deadlockPlant, LeakyCache leakyCache, GcStormService gcStormService,
                            ThreadLeakService threadLeakService, FlakyOrderService flakyOrderService,
                            ResidentWorkers workers) {
        this.deadlockPlant = deadlockPlant;
        this.leakyCache = leakyCache;
        this.gcStormService = gcStormService;
        this.threadLeakService = threadLeakService;
        this.flakyOrderService = flakyOrderService;
        this.workers = workers;
    }

    /** Incident 1: permanent monitor deadlock + a hot lock with BLOCKED waiters. */
    @GetMapping("/deadlock")
    public Map<String, Object> deadlock() {
        workers.submit("deadlock");
        return deadlockPlant.plant();
    }

    /** Incident 2: grow the never-evicted cache by {@code mb} megabytes. */
    @GetMapping("/leak")
    public Map<String, Object> leak(@RequestParam(name = "mb", defaultValue = "64") int mb,
                                    @RequestParam(name = "rows", defaultValue = "0") int rows) {
        workers.submit("leak-" + mb);
        return leakyCache.put(mb, Math.max(0, Math.min(rows, 200_000)));
    }

    @GetMapping("/leak/state")
    public Map<String, Object> leakState() {
        return leakyCache.stats();
    }

    /** Undo incident 2 so the same JVM can be reused for a clean capture. */
    @DeleteMapping("/leak")
    public Map<String, Object> leakReset() {
        return leakyCache.reset();
    }

    @PostMapping("/leak/reset")
    public Map<String, Object> leakResetPost() {
        return leakyCache.reset();
    }

    /**
     * Incident 3: humongous allocation storm that only fits in a capped heap.
     *
     * <p>Async by default so an operator can capture artifacts while the batch is thrashing the
     * collector; pass {@code blocking=true} to make the call wait for the batch to finish.</p>
     */
    @GetMapping("/gcstorm")
    public Map<String, Object> gcstorm(@RequestParam(name = "rounds", defaultValue = "40") int rounds,
                                       @RequestParam(name = "workingSetMb", defaultValue = "0") int workingSetMb,
                                       @RequestParam(name = "blocking", defaultValue = "false") boolean blocking)
            throws InterruptedException {
        workers.submit("gcstorm");
        if (blocking) {
            return gcStormService.stormBlocking(rounds, workingSetMb);
        }
        return gcStormService.storm(rounds, workingSetMb);
    }

    /** Incident 4: leak raw {@code victim-worker-<n>} threads that are never shut down. */
    @GetMapping("/leak-threads")
    public Map<String, Object> leakThreads(@RequestParam(name = "count", defaultValue = "80") int count) {
        return threadLeakService.leak(count);
    }

    /** Incident 5: a cluster of recurring, causally chained exceptions in the app log. */
    @GetMapping("/errors")
    public Map<String, Object> errors(@RequestParam(name = "count", defaultValue = "30") int count) {
        workers.submit("errors");
        return flakyOrderService.burst(count);
    }

    /**
     * Genuinely clean work path: bounded, short-lived, properly released allocations only. Used
     * to capture the {@code corpus/healthy} baseline that the analyzer must return zero findings
     * for. No thread is created, no monitor is entered, no exception is thrown or logged.
     */
    @GetMapping("/healthy")
    public Map<String, Object> healthy(@RequestParam(name = "iterations", defaultValue = "2000") int iterations,
                                       @RequestParam(name = "payload", defaultValue = "16384") int payload) {
        int n = Math.max(0, Math.min(iterations, 20_000));
        int size = Math.max(64, Math.min(payload, 64 * 1024));
        long checksum = 0;
        for (int i = 0; i < n; i++) {
            byte[] buffer = new byte[size];
            Arrays.fill(buffer, (byte) (i & 0x7f));
            String text = new String(buffer, 0, 64, StandardCharsets.US_ASCII);
            checksum += text.length() + buffer[(i * 7) % size];
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("iterations", n);
        body.put("payloadBytes", size);
        body.put("checksum", checksum);
        body.put("heapUsedMb", heap().getUsed() / (1024 * 1024));
        return body;
    }

    /** Liveness/readiness probe. Read-only: allocates nothing and starts nothing. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        MemoryUsage heap = heap();
        var threadMx = ManagementFactory.getThreadMXBean();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("pid", ProcessHandle.current().pid());
        body.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        body.put("heapUsedMb", heap.getUsed() / (1024 * 1024));
        body.put("heapMaxMb", Math.max(0, heap.getMax() / (1024 * 1024)));
        body.put("liveThreads", threadMx.getThreadCount());
        body.put("peakThreads", threadMx.getPeakThreadCount());
        body.put("residentWorkers", workers.threadCount());
        body.put("deadlockPlants", deadlockPlant.plants());
        body.put("leakedWorkers", threadLeakService.stats().get("alive"));
        body.put("cachedEntries", leakyCache.stats().get("entries"));
        body.put("gcRounds", gcStormService.stats().get("roundsCompleted"));
        return body;
    }

    /** Everything the capture scripts and the corpus TRUTH files refer to, in one place. */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        body.put("jvmArgs", ManagementFactory.getRuntimeMXBean().getInputArguments());
        body.put("health", health());
        body.put("leak", leakyCache.stats());
        body.put("gcstorm", gcStormService.stats());
        body.put("threads", threadLeakService.stats());
        body.put("errors", flakyOrderService.stats());
        body.put("deadlockPlants", deadlockPlant.plants());
        return body;
    }

    private static MemoryUsage heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    }
}
