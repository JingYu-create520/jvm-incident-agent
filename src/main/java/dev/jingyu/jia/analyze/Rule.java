package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.Snapshot;

import java.util.List;

/**
 * One deterministic check.
 *
 * <p>A rule reads the parsed snapshot and returns findings. It never talks to an LLM,
 * never mutates the snapshot, and must stay silent on healthy input — the false-positive
 * gate is the whole reason this tool can be trusted.
 */
public interface Rule {

    String id();

    String title();

    ArtifactKind artifact();

    /** Cheap guard so the engine can report a rule as "not applicable" rather than "silent". */
    default boolean applies(Snapshot snapshot) {
        return switch (artifact()) {
            case THREAD_DUMP -> !snapshot.threadDumps().isEmpty();
            case GC_LOG -> snapshot.gcLog().isPresent();
            case HEAP_HISTO -> snapshot.histo().isPresent();
            case APP_LOG -> !snapshot.exceptions().isEmpty();
            case UNKNOWN -> true;
        };
    }

    List<Finding> evaluate(Snapshot snapshot, Config config);

    /**
     * Why this rule declined to answer even though its artifact was present; empty means it ran, and
     * its silence is a result worth reporting as one.
     *
     * <p>Most rules need a minimum of data before a number means anything — a rate needs two
     * collections, a distribution three pauses, a throughput figure a window. Returning nothing in
     * that situation is correct; recording it as {@code clean} is not, because "every rule ran clean
     * against this snapshot" is exactly the sentence a reader carries away from a report with no
     * findings, and a truncated log would make it a lie.
     */
    default String declined(Snapshot snapshot, Config config) {
        return "";
    }

    /**
     * "1 event", "10 events" — the decline and cap sentences quote real counts, and {@code "1 event(s)"}
     * in a report a human reads is the kind of careless detail this project gets judged on.
     */
    static String count(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }

    /** Markdown explanation served by {@code jia explain} and the MCP {@code explain_finding} tool. */
    String doc();
}
