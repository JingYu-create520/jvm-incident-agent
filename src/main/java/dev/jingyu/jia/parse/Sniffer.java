package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.ArtifactKind;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Content-first file identification. Names lie — people rename things, tickets
 * mangle them, and a GC log is still a {@code .log} even though it is nothing like
 * an application log. Signatures are checked first, filenames only break ties.
 */
public final class Sniffer {

    private static final Pattern THREAD_HEADER_LINE = Pattern.compile(
            "^\"[^\"]+\"\\s+(?:#\\d+\\b|(?:daemon\\s+)?(?:prio|os_prio|tid)=).*$");
    /** Unified logs usually lead with a wall-clock decorator, so any number of brackets may precede. */
    private static final Pattern GC_UNIFIED = Pattern.compile(
            "^(?:\\[[^\\]]*\\])*\\[\\d+(?:\\.\\d+)?s\\]\\[[^\\]]*\\]\\[[^\\]]*gc");
    private static final Pattern GC_TRADITIONAL = Pattern.compile("^(?:\\S+:\\s+)?\\d+(?:\\.\\d+)?:\\s+\\[(Full GC|GC)\\b");
    private static final Pattern HISTO_ROW = Pattern.compile("^\\s*\\d+\\s*:\\s*\\d+\\s+\\d+\\s+\\S");
    private static final Pattern STACK_HEAD = Pattern.compile(
            "\\b(?:[A-Za-z_$][\\w$]*\\.)+(?:[A-Z][\\w$]*(?:Exception|Error|Throwable|Fault))\\b");

    private Sniffer() {
    }

    public static ArtifactKind sniff(String fileName, List<String> lines) {
        int scanned = 0;
        int threadHeaders = 0;
        int threadStates = 0;
        int gcLines = 0;
        int histoRows = 0;
        int stackHeads = 0;
        boolean fullDumpMarker = false;

        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            if (++scanned > 4000) {
                break;
            }
            String s = line.strip();
            if (s.startsWith("Full thread dump")) {
                fullDumpMarker = true;
            }
            // find(), not matches(): these are line-start signatures, not whole-line patterns.
            if (s.startsWith("\"") && THREAD_HEADER_LINE.matcher(s).find()) {
                threadHeaders++;
            }
            if (s.startsWith("java.lang.Thread.State:") || s.contains(" java.lang.Thread.State:")) {
                threadStates++;
            }
            if (GC_UNIFIED.matcher(s).find() || GC_TRADITIONAL.matcher(s).find()) {
                gcLines++;
            }
            if (HISTO_ROW.matcher(s).find()) {
                histoRows++;
            }
            if (STACK_HEAD.matcher(s).find() && !s.startsWith("at ")) {
                stackHeads++;
            }
        }

        if (fullDumpMarker || (threadHeaders >= 2 && threadStates >= 1)) {
            return ArtifactKind.THREAD_DUMP;
        }
        if (gcLines >= 2) {
            return ArtifactKind.GC_LOG;
        }
        if (histoRows >= 3) {
            return ArtifactKind.HEAP_HISTO;
        }
        if (stackHeads >= 1) {
            return ArtifactKind.APP_LOG;
        }
        return byName(fileName);
    }

    private static ArtifactKind byName(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".dump") || n.contains("jstack") || n.contains("thread")) {
            return ArtifactKind.THREAD_DUMP;
        }
        if (n.contains("gc") || n.startsWith("gclog") || n.endsWith(".gclog")) {
            return ArtifactKind.GC_LOG;
        }
        if (n.contains("histo") || n.contains("class_histogram")) {
            return ArtifactKind.HEAP_HISTO;
        }
        if (n.endsWith(".log") || n.endsWith(".txt") || n.contains("app")) {
            return ArtifactKind.APP_LOG;
        }
        return ArtifactKind.UNKNOWN;
    }
}
