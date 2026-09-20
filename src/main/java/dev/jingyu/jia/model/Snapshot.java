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

    private final Path root;
    private final List<ThreadDump> threadDumps;
    private final GcLog gcLog;
    private final Histo histo;
    private final List<ExceptionOccurrence> exceptions;
    private final List<Unparsed> unparsed;
    private final List<String> filesSeen;

    private Snapshot(Builder b) {
        this.root = b.root;
        this.threadDumps = List.copyOf(b.threadDumps);
        this.gcLog = b.gcLog;
        this.histo = b.histo;
        this.exceptions = List.copyOf(b.exceptions);
        this.unparsed = List.copyOf(b.unparsed);
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

        public Builder histo(Histo v) {
            this.histo = v;
            return this;
        }

        public boolean hasHisto() {
            return histo != null;
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

        public Builder fileSeen(String name) {
            filesSeen.add(name);
            return this;
        }

        public Snapshot build() {
            return new Snapshot(this);
        }
    }
}
