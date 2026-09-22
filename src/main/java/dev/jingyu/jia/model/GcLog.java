package dev.jingyu.jia.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A parsed GC log: collector, normalised events, and enough bookkeeping to build a timeline. */
public final class GcLog {

    public enum Collector {
        G1, ZGC, SHENANDOAH, PARALLEL, SERIAL, CMS, UNKNOWN;

        public static Collector guess(String haystack) {
            if (haystack == null) {
                return UNKNOWN;
            }
            String s = haystack.toLowerCase(Locale.ROOT);
            if (s.contains("zgc") || s.contains("z garbage collector") || s.contains("pause mark")
                    || s.contains("pause relocate")) {
                return ZGC;
            }
            if (s.contains("shenandoah") || s.contains("pause init mark") || s.contains("pause final mark")) {
                // Shenandoah's own banner is caught by the first test; the mark phases catch a
                // rotated log that starts mid-heap. Its pauses are Init/Final marks around a
                // concurrent cycle, and unlike ZGC it really does run and print Full GCs.
                return SHENANDOAH;
            }
            if (s.contains("g1 ") || s.contains("using g1") || s.contains("g1 eviction")
                    || s.contains("g1 unified young") || s.contains("g1 humongous")
                    || s.contains("pause young (normal)")) {
                return G1;
            }
            if (s.contains("concurrent-mark") || s.contains("cms ") || s.contains("par initial mark")) {
                return CMS;
            }
            if (s.contains("psyounggen") || s.contains("parallel gc") || s.contains("using parallel")) {
                return PARALLEL;
            }
            if (s.contains("defnew") || s.contains("serial gc") || s.contains("using serial")) {
                return SERIAL;
            }
            return UNKNOWN;
        }
    }

    private final TextSource source;
    private final Collector collector;
    private final boolean unified;
    private final List<GcEvent> events;
    private final List<String> notes;
    /** Wall-clock ms of uptime 0, when the log happened to carry both decorators. */
    private final Long bootEpochMillis;

    public GcLog(TextSource source,
                 Collector collector,
                 boolean unified,
                 List<GcEvent> events,
                 List<String> notes,
                 Long bootEpochMillis) {
        this.source = source;
        this.collector = collector;
        this.unified = unified;
        this.events = List.copyOf(events);
        this.notes = List.copyOf(notes);
        this.bootEpochMillis = bootEpochMillis;
    }

    public TextSource source() {
        return source;
    }

    public Collector collector() {
        return collector;
    }

    /** True for JDK 9+ {@code -Xlog:gc*}; false means JDK 8 traditional format. */
    public boolean unified() {
        return unified;
    }

    public List<GcEvent> events() {
        return events;
    }

    public List<String> notes() {
        return notes;
    }

    public Long bootEpochMillis() {
        return bootEpochMillis;
    }

    public List<GcEvent> eventsOfKind(GcEvent.Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).toList();
    }

    public boolean isMajor(GcEvent.Kind kind) {
        if (kind == GcEvent.Kind.FULL) {
            // Shenandoah really does run Full GCs (and prints their occupancy), so the collectors
            // that have one must not lose it while gaining another way to see the live set.
            return true;
        }
        return switch (collector) {
            // ZGC never emits FULL, so the cycle is its only window onto the live set. Shenandoah
            // emits both; its cycles carry no before/after occupancy on JDK 17, which is why the
            // FULL branch above is the one that does the work there.
            case ZGC, SHENANDOAH -> kind == GcEvent.Kind.CYCLE;
            default -> false;
        };
    }

    /**
     * The collections whose "after" number is the live set.
     *
     * <p>Under G1, Parallel, Serial and CMS that is the Full GC. ZGC and Shenandoah never print
     * {@code Pause Full}: their whole-heap work is the concurrent cycle, whose summary line carries
     * the before/after occupancy ({@code GC(99) Garbage Collection (Allocation Rate)
     * 1014M(99%)->1012M(99%)}). Reading only {@link GcEvent.Kind#FULL} there would mean reporting
     * "no Full GC, nothing to see" about a JVM whose heap is pinned at 99 % — the single most
     * misleading silence this tool could produce, so the mapping lives here, where the collector is
     * known.
     */
    public List<GcEvent> majorCollections() {
        return events.stream()
                .filter(e -> isMajor(e.kind()) && e.heapAfterBytes() != null)
                .toList();
    }

    /** How to call one of these in a sentence, plural: "8 Full GC collections", "12 whole-heap cycles". */
    public String majorNoun() {
        // Only ZGC is the case where the cycle replaces a Full GC. Shenandoah prints and runs real
        // Full GCs, so calling its majors "concurrent cycles" would name the wrong thing.
        return collector == Collector.ZGC ? "whole-heap concurrent cycles" : "Full GC collections";
    }

    /** The same, for a line that names a single one: "Full GC at +4.1s" / "cycle at +4.1s". */
    public String majorLabel() {
        return collector == Collector.ZGC ? "cycle" : "Full GC";
    }

    /**
     * ZGC stops the allocating thread itself when the heap cannot keep up — its substitute for a
     * Full GC storm, and it prints the victim's name
     * ({@code Allocation Stall (http-nio-8080-exec-8) 31.866ms}).
     */
    public List<GcEvent> allocationStalls() {
        return eventsOfKind(GcEvent.Kind.ALLOCATION_STALL);
    }

    /**
     * The stop-the-world pauses: durations during which no application thread ran.
     *
     * <p>An allocation stall is deliberately not one of them. It stops the one thread that asked
     * for memory while every other thread keeps going, so adding its milliseconds to a pause total
     * invents application downtime — the same category of error as counting a concurrent cycle's
     * duration as a pause, only smaller. Twenty threads stalling at once under generational ZGC
     * would otherwise add up to more than the wall clock and report a throughput of zero. Stalls
     * belong to GCA007, which says who waited and for how long.
     */
    public List<Double> pauses() {
        List<Double> out = new ArrayList<>();
        for (GcEvent e : events) {
            if (e.kind() == GcEvent.Kind.ALLOCATION_STALL) {
                continue;
            }
            if (e.pauseMs() != null && e.pauseMs() > 0) {
                out.add(e.pauseMs());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Uptime span covered by the log, in seconds. */
    public double durationSec() {
        if (events.size() < 2) {
            return 0.0;
        }
        double min = events.stream().mapToDouble(GcEvent::atSec).min().orElse(0);
        double max = events.stream().mapToDouble(GcEvent::atSec).max().orElse(0);
        return Math.max(0.0, max - min);
    }

    /** Nearest-rank percentile over observed pause times; 0 when there are none. */
    public double pausePercentile(double p) {
        List<Double> all = pauses();
        if (all.isEmpty()) {
            return 0.0;
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        double rank = Math.max(1.0, Math.ceil(p / 100.0 * all.size()));
        return all.get((int) Math.min(all.size(), rank) - 1);
    }

    public double pauseMax() {
        return pauses().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    public double pauseSumMs() {
        return events.stream()
                .filter(e -> e.pauseMs() != null)
                .filter(e -> e.kind() != GcEvent.Kind.ALLOCATION_STALL)
                .mapToDouble(GcEvent::pauseMs)
                .sum();
    }

    /** Total time threads spent waiting for memory under ZGC. Not pause time: see {@link #pauses()}. */
    public double stallSumMs() {
        return allocationStalls().stream()
                .mapToDouble(e -> e.pauseMs() == null ? 0.0 : e.pauseMs())
                .sum();
    }

    /** Share of wall time not spent in a stop-the-world pause. */
    public double throughput() {
        double dur = durationSec() * 1000.0;
        if (dur <= 0) {
            return 1.0;
        }
        return Math.max(0.0, 1.0 - (pauseSumMs() / dur));
    }

    public Long wallEpochMillis(GcEvent e) {
        return bootEpochMillis == null ? null : bootEpochMillis + (long) (e.atSec() * 1000.0);
    }
}
