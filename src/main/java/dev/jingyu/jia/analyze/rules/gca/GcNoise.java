package dev.jingyu.jia.analyze.rules.gca;

import dev.jingyu.jia.model.GcEvent;

/**
 * Full GCs that are real collections but not evidence of heap pressure.
 *
 * <p>Every GC rule that counts collections needs this list, because the causes below arrive in
 * logs from services that are fine — and a rate rule cannot tell them apart on its own. GCA005
 * used to be the only rule that knew, which is how a 78-line startup slice of a healthy Parallel GC
 * service came back from the tool as "Full GC storm, HIGH" on the strength of two
 * {@code Metadata GC Threshold} collections.
 */
public final class GcNoise {

    private GcNoise() {
    }

    /**
     * An outside actor forced this collection: {@code jmap -histo:live}, {@code jcmd
     * GC.class_histogram}, or a heap dump about to be taken. Nothing about the JVM's memory
     * pressure is implied, and the capture tooling in this very repository causes exactly two of
     * them per incident, by design.
     */
    public static boolean toolInduced(GcEvent e) {
        String cause = e.cause();
        return cause != null && (cause.contains("Heap Inspection Initiated GC")
                || cause.contains("Collect For Dumping")
                || cause.contains("Heap Dump Initiated GC"));
    }

    /**
     * Class loading during boot asking for metaspace to be compacted. One or two of these in the
     * first minute of any Spring Boot process is the shape of a normal start; GCA005 reports
     * metaspace pressure at the severity it deserves, and a storm rule must not double-count it as
     * an allocation incident.
     */
    public static boolean metaspaceDuringBoot(GcEvent e, double settleSec) {
        String cause = e.cause();
        return cause != null && cause.contains("Metadata GC Threshold") && e.atSec() <= settleSec;
    }

    public static boolean notHeapPressure(GcEvent e, double settleSec) {
        return toolInduced(e) || metaspaceDuringBoot(e, settleSec);
    }
}
