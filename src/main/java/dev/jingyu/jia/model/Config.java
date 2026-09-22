package dev.jingyu.jia.model;

/**
 * Tunable thresholds for the rule engine. Defaults are the documented ones;
 * everything is overridable from the CLI so a team can state its own SLA.
 *
 * <p>Use {@link #builder()} — the canonical constructor is intentionally not
 * part of the public surface, seventeen positional arguments invite mistakes.
 */
public final class Config {

    private final long slaPauseMs;
    private final double fullGcPerMinute;
    private final long fullGcWindowSec;
    private final int heapLeakMinFullGc;
    private final double heapLeakRiseRatio;
    private final int threadLeakThreshold;
    private final double threadLeakGrowthRatio;
    private final int lockWaiterThreshold;
    private final int stackClusterThreshold;
    private final int poolStarveMinSize;
    private final double poolStarveSameFrameRatio;
    private final double throughputFloor;
    private final int cpuHotThreadTopN;
    private final double cpuHotThreadRatio;
    private final int exceptionClusterThreshold;
    private final double histoDominanceRatio;
    private final int maxEvidencePerFinding;
    /** absolute floor for HIS001: the dominating class must hold at least this many bytes */
    private final long histoTopMinLeaderBytes;
    /** absolute floor for HIS001 when the dominator is a primitive bag (byte[]/char[]/Integer[]) */
    private final long histoTopMinBagBytes;
    /** absolute floor for HIS002: an application class smaller than this is never named */
    private final long histoMatMinBytes;
    /** share of counted bytes HIS002 requires before it calls a class worth a heap dump */
    private final double histoMatMinShare;
    /** floor under HIS003; the rule uses max(this, totalInstances/20) so it scales with big heaps */
    private final long containerMinInstances;
    /** cores of CPU gained between two dumps before TDA006 calls a thread hot */
    private final double cpuHotMinCores;
    /** minimum uptime for a single-dump CPU reading, below which the ratio is noise */
    private final double cpuHotMinElapsedSec;
    /** above this a name family is a leak (TDA003), not a starved pool (TDA005) */
    private final int poolMaxPlausibleSize;
    /** occurrences within one EXC003 bucket before it is called a burst */
    private final int burstMinPerBucket;
    /** width of the EXC003 time bucket */
    private final long burstBucketMillis;
    /** share of capacity at which GCA003 accepts a plateau instead of a climb */
    private final double leakSaturatedShare;
    /** a major collection reclaiming less than this fraction counts as stagnant for GCA003 */
    private final double leakStagnantShare;
    /** how full the heap must be before a dip-free stagnant floor is a finding */
    private final double leakStalledFloorShare;
    /** allocation stalls (GCA007) needed before the stall is a finding rather than a hiccup */
    private final int stallMinCount;

    private Config(Builder b) {
        this.slaPauseMs = b.slaPauseMs;
        this.fullGcPerMinute = b.fullGcPerMinute;
        this.fullGcWindowSec = b.fullGcWindowSec;
        this.heapLeakMinFullGc = b.heapLeakMinFullGc;
        this.heapLeakRiseRatio = b.heapLeakRiseRatio;
        this.threadLeakThreshold = b.threadLeakThreshold;
        this.threadLeakGrowthRatio = b.threadLeakGrowthRatio;
        this.lockWaiterThreshold = b.lockWaiterThreshold;
        this.stackClusterThreshold = b.stackClusterThreshold;
        this.poolStarveMinSize = b.poolStarveMinSize;
        this.poolStarveSameFrameRatio = b.poolStarveSameFrameRatio;
        this.throughputFloor = b.throughputFloor;
        this.cpuHotThreadTopN = b.cpuHotThreadTopN;
        this.cpuHotThreadRatio = b.cpuHotThreadRatio;
        this.exceptionClusterThreshold = b.exceptionClusterThreshold;
        this.histoDominanceRatio = b.histoDominanceRatio;
        this.maxEvidencePerFinding = b.maxEvidencePerFinding;
        this.histoTopMinLeaderBytes = b.histoTopMinLeaderBytes;
        this.histoTopMinBagBytes = b.histoTopMinBagBytes;
        this.histoMatMinBytes = b.histoMatMinBytes;
        this.histoMatMinShare = b.histoMatMinShare;
        this.containerMinInstances = b.containerMinInstances;
        this.cpuHotMinCores = b.cpuHotMinCores;
        this.cpuHotMinElapsedSec = b.cpuHotMinElapsedSec;
        this.poolMaxPlausibleSize = b.poolMaxPlausibleSize;
        this.burstMinPerBucket = b.burstMinPerBucket;
        this.burstBucketMillis = b.burstBucketMillis;
        this.leakSaturatedShare = b.leakSaturatedShare;
        this.leakStagnantShare = b.leakStagnantShare;
        this.leakStalledFloorShare = b.leakStalledFloorShare;
        this.stallMinCount = b.stallMinCount;
    }

    public static Config defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public long slaPauseMs() {
        return slaPauseMs;
    }

    public double fullGcPerMinute() {
        return fullGcPerMinute;
    }

    public long fullGcWindowSec() {
        return fullGcWindowSec;
    }

    public int heapLeakMinFullGc() {
        return heapLeakMinFullGc;
    }

    public double heapLeakRiseRatio() {
        return heapLeakRiseRatio;
    }

    public int threadLeakThreshold() {
        return threadLeakThreshold;
    }

    public double threadLeakGrowthRatio() {
        return threadLeakGrowthRatio;
    }

    public int lockWaiterThreshold() {
        return lockWaiterThreshold;
    }

    public int stackClusterThreshold() {
        return stackClusterThreshold;
    }

    public int poolStarveMinSize() {
        return poolStarveMinSize;
    }

    public double poolStarveSameFrameRatio() {
        return poolStarveSameFrameRatio;
    }

    public double throughputFloor() {
        return throughputFloor;
    }

    public int cpuHotThreadTopN() {
        return cpuHotThreadTopN;
    }

    public double cpuHotThreadRatio() {
        return cpuHotThreadRatio;
    }

    public int exceptionClusterThreshold() {
        return exceptionClusterThreshold;
    }

    public double histoDominanceRatio() {
        return histoDominanceRatio;
    }

    public int maxEvidencePerFinding() {
        return maxEvidencePerFinding;
    }
    /** absolute floor for HIS001: the dominating class must hold at least this many bytes */
    public long histoTopMinLeaderBytes() {
        return histoTopMinLeaderBytes;
    }
    /** absolute floor for HIS001 when the dominator is a primitive bag (byte[]/char[]/Integer[]) */
    public long histoTopMinBagBytes() {
        return histoTopMinBagBytes;
    }
    /** absolute floor for HIS002: an application class smaller than this is never named */
    public long histoMatMinBytes() {
        return histoMatMinBytes;
    }
    /** share of counted bytes HIS002 requires before it calls a class worth a heap dump */
    public double histoMatMinShare() {
        return histoMatMinShare;
    }
    /** floor under HIS003; the rule uses max(this, totalInstances/20) so it scales with big heaps */
    public long containerMinInstances() {
        return containerMinInstances;
    }
    /** cores of CPU gained between two dumps before TDA006 calls a thread hot */
    public double cpuHotMinCores() {
        return cpuHotMinCores;
    }
    /** minimum uptime for a single-dump CPU reading, below which the ratio is noise */
    public double cpuHotMinElapsedSec() {
        return cpuHotMinElapsedSec;
    }
    /** above this a name family is a leak (TDA003), not a starved pool (TDA005) */
    public int poolMaxPlausibleSize() {
        return poolMaxPlausibleSize;
    }
    /** occurrences within one EXC003 bucket before it is called a burst */
    public int burstMinPerBucket() {
        return burstMinPerBucket;
    }
    /** width of the EXC003 time bucket */
    public long burstBucketMillis() {
        return burstBucketMillis;
    }
    /** share of capacity at which GCA003 accepts a plateau instead of a climb */
    public double leakSaturatedShare() {
        return leakSaturatedShare;
    }
    /** a major collection reclaiming less than this fraction counts as stagnant for GCA003 */
    public double leakStagnantShare() {
        return leakStagnantShare;
    }
    /** how full the heap must be before a dip-free stagnant floor is a finding */
    public double leakStalledFloorShare() {
        return leakStalledFloorShare;
    }
    /** allocation stalls (GCA007) needed before the stall is a finding rather than a hiccup */
    public int stallMinCount() {
        return stallMinCount;
    }

    /**
     * Every threshold in force, under the name the CLI uses. The JSON report prints this so that a
     * reader who disagrees with a boundary decision can see which number made it, and reproduce the
     * call by passing the flag — a claim that cannot be re-run at a different threshold is not a
     * claim, it is a coincidence of the default.
     */
    public java.util.Map<String, String> asMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("sla-ms", String.valueOf(slaPauseMs));
        m.put("full-gc-per-min", String.valueOf(fullGcPerMinute));
        m.put("full-gc-window-sec", String.valueOf(fullGcWindowSec));
        m.put("heap-leak-min-full-gc", String.valueOf(heapLeakMinFullGc));
        m.put("heap-leak-rise", String.valueOf(heapLeakRiseRatio));
        m.put("thread-leak-threshold", String.valueOf(threadLeakThreshold));
        m.put("thread-leak-growth", String.valueOf(threadLeakGrowthRatio));
        m.put("lock-waiters", String.valueOf(lockWaiterThreshold));
        m.put("stack-cluster", String.valueOf(stackClusterThreshold));
        m.put("pool-starve-min-size", String.valueOf(poolStarveMinSize));
        m.put("pool-starve-same-frame", String.valueOf(poolStarveSameFrameRatio));
        m.put("throughput", String.valueOf(throughputFloor));
        m.put("cpu-hot-top-n", String.valueOf(cpuHotThreadTopN));
        m.put("cpu-hot-ratio", String.valueOf(cpuHotThreadRatio));
        m.put("exception-threshold", String.valueOf(exceptionClusterThreshold));
        m.put("histo-share", String.valueOf(histoDominanceRatio));
        m.put("max-evidence", String.valueOf(maxEvidencePerFinding));
        m.put("histo-top-min-leader-mb", String.valueOf(histoTopMinLeaderBytes / (1024 * 1024)));
        m.put("histo-top-min-bag-mb", String.valueOf(histoTopMinBagBytes / (1024 * 1024)));
        m.put("mat-min-mb", String.valueOf(histoMatMinBytes / (1024 * 1024)));
        m.put("mat-share", String.valueOf(histoMatMinShare));
        m.put("container-min-instances", String.valueOf(containerMinInstances));
        m.put("cpu-min-cores", String.valueOf(cpuHotMinCores));
        m.put("cpu-min-elapsed-sec", String.valueOf(cpuHotMinElapsedSec));
        m.put("pool-max-size", String.valueOf(poolMaxPlausibleSize));
        m.put("burst-min", String.valueOf(burstMinPerBucket));
        m.put("burst-bucket-sec", String.valueOf(burstBucketMillis / 1000));
        m.put("leak-pinned-share", String.valueOf(leakSaturatedShare));
        m.put("leak-stagnant-share", String.valueOf(leakStagnantShare));
        m.put("leak-stalled-floor", String.valueOf(leakStalledFloorShare));
        m.put("stall-min", String.valueOf(stallMinCount));
        return m;
    }


    public static final class Builder {
        private long slaPauseMs = 200L;
        private double fullGcPerMinute = 1.0;
        private long fullGcWindowSec = 300L;
        private int heapLeakMinFullGc = 3;
        private double heapLeakRiseRatio = 0.10;
        private int threadLeakThreshold = 40;
        private double threadLeakGrowthRatio = 1.25;
        private int lockWaiterThreshold = 3;
        private int stackClusterThreshold = 5;
        private int poolStarveMinSize = 4;
        private double poolStarveSameFrameRatio = 0.80;
        private double throughputFloor = 0.97;
        private int cpuHotThreadTopN = 3;
        private double cpuHotThreadRatio = 0.25;
        private int exceptionClusterThreshold = 5;
        private double histoDominanceRatio = 0.35;
        private int maxEvidencePerFinding = 6;
        private long histoTopMinLeaderBytes = 16L * 1024 * 1024;
        private long histoTopMinBagBytes = 32L * 1024 * 1024;
        private long histoMatMinBytes = 8L * 1024 * 1024;
        private double histoMatMinShare = 0.10;
        private long containerMinInstances = 50_000L;
        private double cpuHotMinCores = 0.5;
        private double cpuHotMinElapsedSec = 1.0;
        private int poolMaxPlausibleSize = 100;
        private int burstMinPerBucket = 10;
        private long burstBucketMillis = 60_000L;
        private double leakSaturatedShare = 0.85;
        private double leakStagnantShare = 0.02;
        private double leakStalledFloorShare = 0.40;
        private int stallMinCount = 3;

        public Builder slaPauseMs(long v) {
            this.slaPauseMs = v;
            return this;
        }

        public Builder fullGcPerMinute(double v) {
            this.fullGcPerMinute = v;
            return this;
        }

        public Builder fullGcWindowSec(long v) {
            this.fullGcWindowSec = v;
            return this;
        }

        public Builder heapLeakMinFullGc(int v) {
            this.heapLeakMinFullGc = v;
            return this;
        }

        public Builder heapLeakRiseRatio(double v) {
            this.heapLeakRiseRatio = v;
            return this;
        }

        public Builder threadLeakThreshold(int v) {
            this.threadLeakThreshold = v;
            return this;
        }

        public Builder threadLeakGrowthRatio(double v) {
            this.threadLeakGrowthRatio = v;
            return this;
        }

        public Builder lockWaiterThreshold(int v) {
            this.lockWaiterThreshold = v;
            return this;
        }

        public Builder stackClusterThreshold(int v) {
            this.stackClusterThreshold = v;
            return this;
        }

        public Builder poolStarveMinSize(int v) {
            this.poolStarveMinSize = v;
            return this;
        }

        public Builder poolStarveSameFrameRatio(double v) {
            this.poolStarveSameFrameRatio = v;
            return this;
        }

        public Builder throughputFloor(double v) {
            this.throughputFloor = v;
            return this;
        }

        public Builder cpuHotThreadTopN(int v) {
            this.cpuHotThreadTopN = v;
            return this;
        }

        public Builder cpuHotThreadRatio(double v) {
            this.cpuHotThreadRatio = v;
            return this;
        }

        public Builder exceptionClusterThreshold(int v) {
            this.exceptionClusterThreshold = v;
            return this;
        }

        public Builder histoDominanceRatio(double v) {
            this.histoDominanceRatio = v;
            return this;
        }

        public Builder maxEvidencePerFinding(int v) {
            this.maxEvidencePerFinding = v;
            return this;
        }

        public Builder histoTopMinLeaderBytes(long v) {
            this.histoTopMinLeaderBytes = v;
            return this;
        }
        public Builder histoTopMinBagBytes(long v) {
            this.histoTopMinBagBytes = v;
            return this;
        }
        public Builder histoMatMinBytes(long v) {
            this.histoMatMinBytes = v;
            return this;
        }
        public Builder histoMatMinShare(double v) {
            this.histoMatMinShare = v;
            return this;
        }
        public Builder containerMinInstances(long v) {
            this.containerMinInstances = v;
            return this;
        }
        public Builder cpuHotMinCores(double v) {
            this.cpuHotMinCores = v;
            return this;
        }
        public Builder cpuHotMinElapsedSec(double v) {
            this.cpuHotMinElapsedSec = v;
            return this;
        }
        public Builder poolMaxPlausibleSize(int v) {
            this.poolMaxPlausibleSize = v;
            return this;
        }
        public Builder burstMinPerBucket(int v) {
            this.burstMinPerBucket = v;
            return this;
        }
        public Builder burstBucketMillis(long v) {
            this.burstBucketMillis = v;
            return this;
        }
        public Builder leakSaturatedShare(double v) {
            this.leakSaturatedShare = v;
            return this;
        }
        public Builder leakStagnantShare(double v) {
            this.leakStagnantShare = v;
            return this;
        }
        public Builder leakStalledFloorShare(double v) {
            this.leakStalledFloorShare = v;
            return this;
        }
        public Builder stallMinCount(int v) {
            this.stallMinCount = v;
            return this;
        }
        public Config build() {
            return new Config(this);
        }
    }
}
