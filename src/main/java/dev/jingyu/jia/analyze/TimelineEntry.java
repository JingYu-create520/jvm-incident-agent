package dev.jingyu.jia.analyze;

import dev.jingyu.jia.model.ArtifactKind;

/**
 * One line of the reconstructed incident timeline.
 *
 * <p>{@code epochMillis} and {@code uptimeSec} are both optional on purpose: some
 * sources carry wall clock, some only JVM uptime. When the GC log exposes both, the
 * analyzer derives the JVM start time and puts everything on one axis; otherwise the
 * report says so instead of pretending the ordering is exact.
 */
public record TimelineEntry(Long epochMillis,
                            Double uptimeSec,
                            String label,
                            String detail,
                            ArtifactKind source,
                            String file,
                            Integer line,
                            boolean major) {
}
