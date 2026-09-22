package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.model.ThreadDump;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Turns a directory (or one file, or pasted text) into a {@link Snapshot}.
 *
 * <p>Nothing here fails the run. A file that cannot be read or understood becomes an
 * {@code unparsed} entry with a reason, which the engine reports as INFO — a partial
 * analysis beats an error message that tells the reader nothing.
 */
public final class SnapshotLoader {

    /** Guards against someone pointing the tool at a heap dump or a tarball. */
    private static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_DEPTH = 3;

    private SnapshotLoader() {
    }

    public static ParseReport<Snapshot> load(Path target) {
        List<String> notes = new ArrayList<>();
        Snapshot.Builder b = Snapshot.builder();
        try {
            if (Files.isDirectory(target)) {
                b.root(target);
                List<Path> files = new ArrayList<>();
                try (var stream = Files.walk(target, MAX_DEPTH)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> !isNoise(p.getFileName().toString()))
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(files::add);
                }
                if (files.isEmpty()) {
                    return ParseReport.of(b.build(), List.of("no files found under " + target));
                }
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    try {
                        ingest(b, notes, name, readBytes(f, notes));
                    } catch (RuntimeException e) {
                        // One hostile file must not cost the reader the other three.
                        b.addUnparsed(new Snapshot.Unparsed(name, "parser threw " + e.getClass().getSimpleName()));
                        notes.add(name + ": " + e);
                    }
                }
                return finish(b, notes);
            }
            if (Files.isRegularFile(target)) {
                String name = target.getFileName().toString();
                try {
                    ingest(b, notes, name, readBytes(target, notes));
                } catch (RuntimeException e) {
                    b.addUnparsed(new Snapshot.Unparsed(name, "parser threw " + e.getClass().getSimpleName()));
                    notes.add(name + ": " + e);
                }
                return finish(b, notes);
            }
        } catch (IOException | UncheckedIOException e) {
            notes.add("cannot read " + target + ": " + e.getMessage());
        }
        return finish(b, notes);
    }

    /** For the MCP surface, where a chat agent pastes dump text instead of a path. */
    public static ParseReport<Snapshot> loadText(String fileName, String content) {
        List<String> notes = new ArrayList<>();
        Snapshot.Builder b = Snapshot.builder();
        byte[] raw = content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            ingest(b, notes, fileName, raw);
        } catch (RuntimeException e) {
            b.addUnparsed(new Snapshot.Unparsed(fileName, "parser threw " + e.getClass().getSimpleName()));
            notes.add(fileName + ": " + e);
        }
        return finish(b, notes);
    }

    private static boolean isNoise(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith(".") || n.endsWith(".hprof") || n.endsWith(".zip") || n.endsWith(".gz")
                || n.endsWith(".tar") || n.endsWith(".jfr") || n.equals("report.md") || n.equals("report.json");
    }

    private static byte[] readBytes(Path f, List<String> notes) {
        try {
            long size = Files.size(f);
            if (size > MAX_FILE_BYTES) {
                notes.add(f.getFileName() + " skipped: " + size + " bytes exceeds the 128 MB input limit");
                return null;
            }
            return Files.readAllBytes(f);
        } catch (IOException e) {
            notes.add(f.getFileName() + " could not be read: " + e.getMessage());
            return null;
        }
    }

    private static void ingest(Snapshot.Builder b, List<String> notes, String fileName, byte[] raw) {
        if (raw == null) {
            return;
        }
        b.fileSeen(fileName);
        List<String> lines = TextFiles.decode(raw);
        if (lines.isEmpty()) {
            b.addUnparsed(new Snapshot.Unparsed(fileName, "file is empty"));
            return;
        }
        ArtifactKind kind = Sniffer.sniff(fileName, lines);
        TextSource src = TextSource.of(fileName, lines);
        switch (kind) {
            case THREAD_DUMP -> {
                ParseReport<List<ThreadDump>> r = ThreadDumpParser.parse(src);
                notes.addAll(r.notes());
                if (r.value().isEmpty()) {
                    b.addUnparsed(new Snapshot.Unparsed(fileName, "thread dump format not recognised"));
                } else {
                    r.value().forEach(b::addDump);
                }
            }
            case GC_LOG -> {
                ParseReport<GcLog> r = GcLogParser.parse(src);
                notes.addAll(r.notes());
                if (r.value().events().isEmpty()) {
                    b.addUnparsed(new Snapshot.Unparsed(fileName, "no GC events recognised"));
                } else if (b.hasGcLog()) {
                    // A rotated -Xlog set (gc.log, gc.log.0, …) is the normal way to find a GC log, and
                    // a snapshot can carry one. Saying so is the difference between a partial report and
                    // a report that presents half the incident as the whole of it. Files are walked in
                    // sorted order and JDK rotation leaves the live window in the suffix-less file, so
                    // the one kept here is also the newest — which is why the disclosure names it.
                    b.skip("GC log", b.gcLogValue().get().source().name(), fileName,
                            Snapshot.Skipped.GC_LOG_ADVICE);
                } else {
                    b.gcLog(r.value());
                }
            }
            case HEAP_HISTO -> {
                ParseReport<Histo> r = HistoParser.parse(src);
                notes.addAll(r.notes());
                if (r.value().empty()) {
                    b.addUnparsed(new Snapshot.Unparsed(fileName, "no histogram rows recognised"));
                } else if (b.hasHisto()) {
                    b.skip("heap histogram", b.histoValue().get().source().name(), fileName,
                            Snapshot.Skipped.HISTO_ADVICE);
                } else {
                    b.histo(r.value());
                }
            }
            case APP_LOG -> {
                ParseReport<List<ExceptionOccurrence>> r = StackParser.parse(src);
                notes.addAll(r.notes());
                if (r.value().isEmpty()) {
                    b.addUnparsed(new Snapshot.Unparsed(fileName, "no exception stacks found"));
                } else {
                    r.value().forEach(b::addException);
                }
            }
            default -> b.addUnparsed(new Snapshot.Unparsed(fileName, "unrecognised artifact type"));
        }
    }

    /**
     * Close a load: the inputs that were dropped are a fact about the snapshot, and the stderr notes
     * a run with nothing recognised prints are generated from the same sentence the report shows. One
     * source, so the two channels cannot contradict each other.
     */
    private static ParseReport<Snapshot> finish(Snapshot.Builder b, List<String> notes) {
        b.skippedSoFar().forEach(s -> notes.add(s.sentence()));
        return ParseReport.of(b.build(), notes);
    }
}
