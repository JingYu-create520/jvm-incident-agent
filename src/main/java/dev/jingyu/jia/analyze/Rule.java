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

    /** Markdown explanation served by {@code jia explain} and the MCP {@code explain_finding} tool. */
    String doc();
}
