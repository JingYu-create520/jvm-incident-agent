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

        public Config build() {
            return new Config(this);
        }
    }
}
