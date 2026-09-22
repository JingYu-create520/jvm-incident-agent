package dev.jingyu.jia.model;

/**
 * A single collected event, normalised across JDK 8 traditional output and JDK 9+
 * unified {@code -Xlog:gc*} output.
 *
 * <p>{@code atSec} is JVM uptime in seconds when the log carries an uptime
 * decorator, otherwise an offset from the first stamped line. Rules that reason
 * about rates only need it monotonic and in seconds.
 */
public record GcEvent(int index,
                      Kind kind,
                      String cause,
                      double atSec,
                      Double pauseMs,
                      Long heapBeforeBytes,
                      Long heapAfterBytes,
                      Long capacityBytes,
                      Long oldAfterBytes,
                      Long metaspaceAfterBytes,
                      boolean toSpaceExhausted,
                      boolean humongous,
                      String wallClockRaw,
                      int line) {

    public enum Kind {
        YOUNG, MIXED, FULL, REMARK, CLEANUP, CYCLE, ALLOCATION_STALL, OTHER;

        /**
         * Major collections are the ones that stop the world and reveal the live set — for the
         * collectors that work that way. ZGC has no Full GC in its vocabulary at all; its
         * equivalent is the concurrent cycle, and that mapping lives in
         * {@link GcLog#majorCollections()} rather than here, because it needs the collector.
         */
        public boolean major() {
            return this == FULL;
        }
    }

    public Double heapBeforeMb() {
        return heapBeforeBytes == null ? null : heapBeforeBytes / 1048576.0;
    }

    public Double heapAfterMb() {
        return heapAfterBytes == null ? null : heapAfterBytes / 1048576.0;
    }

    public Double capacityMb() {
        return capacityBytes == null ? null : capacityBytes / 1048576.0;
    }

    public Long oldAfterMb() {
        return oldAfterBytes == null ? null : oldAfterBytes / 1048576L;
    }

    public Double reclaimedMb() {
        if (heapBeforeBytes == null || heapAfterBytes == null) {
            return null;
        }
        return (heapBeforeBytes - heapAfterBytes) / 1048576.0;
    }
}
