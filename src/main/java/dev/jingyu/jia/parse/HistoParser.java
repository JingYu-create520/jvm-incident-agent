package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.ClassStat;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.TextSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code jmap -histo[:live]} and {@code jcmd GC.class_histogram} tables.
 *
 * <p>The shape has been stable since JDK 5 apart from the module suffix JDK 9 adds
 * to the class column, and the odd {@code <bootstrap>} qualifier.
 */
public final class HistoParser {

    private static final Pattern ROW = Pattern.compile(
            "^\\s*(\\d+)\\s*:\\s*(\\d+)\\s+(\\d+)\\s+(\\S.*?)\\s*$");
    private static final Pattern TOTAL = Pattern.compile(
            "^\\s*(?:total)\\s+(\\d+)\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*num\\s+#instances\\s+#bytes\\s+class name", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIVE_MARK = Pattern.compile(":live|live dump|Number of objects in the heap",
            Pattern.CASE_INSENSITIVE);
    /** Trailing module qualifier: {@code [B (java.base@17.0.5)} or {@code Foo (<bootstrap>)}. */
    private static final Pattern MODULE_SUFFIX =
            Pattern.compile("\\s\\((?:<\\w+>|[\\w.$-]+(?:@[\\w.+\\-]+)?)\\)\\s*$");

    private HistoParser() {
    }

    public static ParseReport<Histo> parse(TextSource src) {
        List<ClassStat> classes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean live = false;
        long totalBytes = 0;
        long totalInstances = 0;
        boolean sawTotal = false;
        int unparsed = 0;

        List<String> lines = src.lines();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (line.isBlank()) {
                continue;
            }
            if (!live && LIVE_MARK.matcher(line).find()) {
                live = true;
            }
            if (HEADER.matcher(line).find()) {
                continue;
            }
            Matcher t = TOTAL.matcher(line);
            if (t.matches()) {
                totalInstances = Long.parseLong(t.group(1));
                totalBytes = Long.parseLong(t.group(2));
                sawTotal = true;
                continue;
            }
            if (line.matches("^\\s*-+\\s*$")) {
                continue;
            }
            Matcher r = ROW.matcher(line);
            if (!r.matches()) {
                if (!line.startsWith("Attaching") && !line.startsWith("num")) {
                    unparsed++;
                }
                continue;
            }
            String cls = MODULE_SUFFIX.matcher(r.group(4)).replaceAll("").strip();
            if (cls.isEmpty()) {
                continue;
            }
            classes.add(new ClassStat(cls, Long.parseLong(r.group(2)), Long.parseLong(r.group(3)), lineNo));
        }

        if (classes.isEmpty()) {
            notes.add("no histogram rows found in " + src.name());
        }
        if (!sawTotal) {
            long b = 0;
            long n = 0;
            for (ClassStat c : classes) {
                b += c.bytes();
                n += c.instances();
            }
            totalBytes = b;
            totalInstances = n;
            if (!classes.isEmpty()) {
                notes.add("no Total row in " + src.name() + "; totals summed from visible rows");
            }
        }
        if (unparsed > 0) {
            notes.add(unparsed + " unrecognised line(s) in " + src.name());
        }
        return ParseReport.of(new Histo(src, live, List.copyOf(classes), totalBytes, totalInstances), notes);
    }
}
