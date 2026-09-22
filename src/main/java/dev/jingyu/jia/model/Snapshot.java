package dev.jingyu.jia.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything the analyzer could read out of one incident snapshot directory.
 *
 * <p>Any member may be absent — a lone {@code dump.txt} is a valid snapshot. Rules
 * declare which artifact they need and are skipped when it is missing.
 */
public final class Snapshot {

    /** A file the loader could not attribute to any known format. */
    public record Unparsed(String file, String reason) {
    }

    /**
     * An input that was read off disk and deliberately left out of the analysis — a rotated GC log
     * behind the one that was read, a second histogram of a process that can only be measured once.
     * Gathered by kind, because a folder with four rotations is one fact for the reader, not four.
     */
    public record Skipped(String kind, String kept, List<String> dropped, String advice) {

        /** What to do about a dropped input, written once: the loader and the merge cannot drift. */
        public static final String GC_LOG_ADVICE =
                "Pass the rotations one at a time to analyse each window on its own.";
        public static final String HISTO_ADVICE =
                "Two captures are two instants; run each one separately to compare them.";

        /**
         * One sentence. Both report surfaces show prose here, and an agent reading the JSON should
         * reach the same conclusion the operator reaches from the Markdown.
         */
        public String sentence() {
            boolean many = dropped.size() > 1;
            return kind + (many ? "s " : " ") + "`" + String.join("`, `", dropped) + "`"
                    + (many ? " were" : " was") + " read off disk and not analysed: a run reads exactly one, "
                    + "and `" + kept + "` is the one these findings describe. " + advice;
        }
    }

    private final Path root;
    private final List<ThreadDump> threadDumps;
    private final GcLog gcLog;
    private final Histo histo;
    private final List<ExceptionOccurrence> exceptions;
    private final List<Unparsed> unparsed;
    /** What the loader decided that no rule can see, and therefore cannot disclose on its own. */
    private final List<Skipped> skipped;
    private final List<String> filesSeen;

    private Snapshot(Builder b) {
        this.root = b.root;
        this.threadDumps = List.copyOf(b.threadDumps);
        this.gcLog = b.gcLog;
        this.histo = b.histo;
        this.exceptions = List.copyOf(b.exceptions);
        this.unparsed = List.copyOf(b.unparsed);
        this.skipped = List.copyOf(b.skipped);
        this.filesSeen = List.copyOf(b.filesSeen);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Path> root() {
        return Optional.ofNullable(root);
    }

    public List<ThreadDump> threadDumps() {
        return threadDumps;
    }

    public Optional<ThreadDump> latestDump() {
        return threadDumps.isEmpty() ? Optional.empty() : Optional.of(threadDumps.get(threadDumps.size() - 1));
    }

    public Optional<ThreadDump> firstDump() {
        return threadDumps.isEmpty() ? Optional.empty() : Optional.of(threadDumps.get(0));
    }

    public Optional<GcLog> gcLog() {
        return Optional.ofNullable(gcLog);
    }

    public Optional<Histo> histo() {
        return Optional.ofNullable(histo);
    }

    public List<ExceptionOccurrence> exceptions() {
        return exceptions;
    }

    public List<Unparsed> unparsed() {
        return unparsed;
    }

    public List<Skipped> skipped() {
        return skipped;
    }

    /** The same facts as sentences, for a surface that has no structure to put them in. */
    public List<String> skippedSentences() {
        return skipped.stream().map(Skipped::sentence).toList();
    }

    public List<String> filesSeen() {
        return filesSeen;
    }

    public List<JThread> allThreads() {
        List<JThread> out = new ArrayList<>();
        threadDumps.forEach(d -> out.addAll(d.threads()));
        return out;
    }

    /** Distinct thread name families across every dump, keyed by family. */
    public Optional<String> describe() {
        List<String> parts = new ArrayList<>();
        if (!threadDumps.isEmpty()) {
            parts.add(threadDumps.size() + " thread dump(s)");
        }
        if (gcLog != null) {
            parts.add("GC log (" + gcLog.events().size() + " events)");
        }
        if (histo != null) {
            parts.add("heap histogram (" + histo.classes().size() + " classes)");
        }
        if (!exceptions.isEmpty()) {
            parts.add(exceptions.size() + " exception stacks");
        }
        return parts.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", parts));
    }

    public static final class Builder {
        private Path root;
        private final List<ThreadDump> threadDumps = new ArrayList<>();
        private GcLog gcLog;
        private Histo histo;
        private final List<ExceptionOccurrence> exceptions = new ArrayList<>();
        private final List<Unparsed> unparsed = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private final List<String> filesSeen = new ArrayList<>();

        public Builder root(Path v) {
            this.root = v;
            return this;
        }

        public Builder addDump(ThreadDump v) {
            threadDumps.add(v);
            return this;
        }

        public Builder gcLog(GcLog v) {
            this.gcLog = v;
            return this;
        }

        public boolean hasGcLog() {
            return gcLog != null;
        }

        /** What this snapshot already holds, so a "this file was skipped" note can name the survivor. */
        public Optional<GcLog> gcLogValue() {
            return Optional.ofNullable(gcLog);
        }

        public Builder histo(Histo v) {
            this.histo = v;
            return this;
        }

        public boolean hasHisto() {
            return histo != null;
        }

        public Optional<Histo> histoValue() {
            return Optional.ofNullable(histo);
        }

        public Builder addException(ExceptionOccurrence v) {
            exceptions.add(v);
            return this;
        }

        public Builder exceptions(List<ExceptionOccurrence> v) {
            exceptions.addAll(v);
            return this;
        }

        public Builder addUnparsed(Unparsed v) {
            unparsed.add(v);
            return this;
        }

        public Builder skip(String kind, String kept, String dropped, String advice) {
            return skip(new Skipped(kind, kept, List.of(dropped), advice));
        }

        /** Merged by kind and survivor: four rotations behind one GC log are one disclosure, not four. */
        public Builder skip(Skipped v) {
            for (int i = 0; i < skipped.size(); i++) {
                Skipped s = skipped.get(i);
                if (s.kind().equals(v.kind()) && s.kept().equals(v.kept())) {
                    List<String> all = new ArrayList<>(s.dropped());
                    all.addAll(v.dropped());
                    skipped.set(i, new Skipped(s.kind(), s.kept(), List.copyOf(all), s.advice()));
                    return this;
                }
            }
            skipped.add(v);
            return this;
        }

        /** What the loader has dropped so far, for a caller that must merge across several inputs. */
        public List<Skipped> skippedSoFar() {
            return List.copyOf(skipped);
        }

        public Builder fileSeen(String name) {
            filesSeen.add(name);
            return this;
        }

        public Snapshot build() {
            return new Snapshot(this);
        }
    }
}
