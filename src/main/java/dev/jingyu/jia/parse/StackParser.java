package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.Frame;
import dev.jingyu.jia.model.TextSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exception stacks out of an arbitrary application log.
 *
 * <p>The gate is deliberately strict: a header class name is only accepted when at
 * least one {@code at ...} frame follows it. Single-line warnings that merely mention
 * {@code java.io.IOException: reset} are extremely common in healthy logs, and
 * counting them would break the "healthy sample yields zero findings" contract.
 */
public final class StackParser {

    /**
     * A throwable-looking class name: dotted packages, optional inner-class {@code $}, and a last
     * segment ending in Exception/Error/Throwable/Fault. The suffix sits inside the capture, so
     * {@code java.net.SocketTimeoutException} is never reported as {@code java.net.SocketTimeout}.
     */
    private static final String THROWABLE =
            "((?:[A-Za-z_$][\\w$]*[.$])*[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable|Fault))";

    private static final Pattern HEAD = Pattern.compile(
            "^\\s*(?:(Caused by|Suppressed)\\s*:\\s*)?" + THROWABLE + "(?::\\s?(.*))?$");

    /** The same, after a logback/log4j prefix: {@code 2026-… ERROR c.e.Svc : java.io.IOException: …}. */
    private static final Pattern HEAD_AFTER_PREFIX = Pattern.compile(
            "^[^\\n]*?\\b(?:ERROR|SEVERE|FATAL|WARN|WARNING)\\b[^\\n]*?\\s[:\\-]?\\s*"
                    + THROWABLE + "(?::\\s?(.*))?$");

    private static final Pattern MORE = Pattern.compile("^\\s*\\.\\.\\.\\s*(\\d+)\\s*(more|common frames omitted)"
            + "|^\\s*\\.\\.\\.\\s*\\d+\\s+filtered");

    private static boolean isFrameLine(String line) {
        return line != null && line.stripLeading().startsWith("at ");
    }

    private StackParser() {
    }

    public static ParseReport<List<ExceptionOccurrence>> parse(TextSource src) {
        List<ExceptionOccurrence> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> lines = src.lines();

        int i = 0;
        while (i < lines.size()) {
            Head head = matchHead(lines.get(i));
            if (head == null || head.kind.equals("Suppressed")) {
                i++;
                continue;
            }
            Block block = readBlock(lines, i + 1, head);
            if (block.totalFrames() == 0) {
                // Header alone: almost always a log message, not a stack trace.
                i++;
                continue;
            }
            Long epoch = stampFor(lines, i);
            String level = levelOf(lines.get(i));
            out.add(new ExceptionOccurrence(src, block.chain, epoch, timestampOf(lines, i), level, i + 1,
                    block.lastLine));
            i = block.nextIndex;
        }

        if (out.isEmpty()) {
            notes.add("no exception stacks found in " + src.name());
        }
        return ParseReport.of(List.copyOf(out), notes);
    }

    private record Head(String kind, String className, String message) {
    }

    private static Head matchHead(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher m = HEAD.matcher(line);
        if (m.matches()) {
            return new Head(m.group(1) == null ? "root" : m.group(1), m.group(2), m.group(3));
        }
        Matcher m2 = HEAD_AFTER_PREFIX.matcher(line);
        if (m2.matches()) {
            return new Head("root", m2.group(1), m2.group(2));
        }
        return null;
    }

    private static final class Block {
        private final List<ExceptionOccurrence.CauseNode> chain = new ArrayList<>();
        private List<Frame> frames = new ArrayList<>();
        private String currentClass;
        private String currentMessage;
        private int lastLine;
        private int nextIndex;

        Block(String cls, String msg, int lineNo) {
            this.currentClass = cls;
            this.currentMessage = msg;
            this.lastLine = lineNo;
        }

        void close() {
            if (currentClass != null) {
                chain.add(new ExceptionOccurrence.CauseNode(currentClass, nullToEmpty(currentMessage),
                        List.copyOf(frames)));
            }
            frames = new ArrayList<>();
        }

        void startCause(String cls, String msg) {
            close();
            currentClass = cls;
            currentMessage = msg;
        }

        int totalFrames() {
            int n = frames.size();
            for (ExceptionOccurrence.CauseNode c : chain) {
                n += c.frames().size();
            }
            return n;
        }
    }

    private static Block readBlock(List<String> lines, int from, Head head) {
        Block b = new Block(head.className(), head.message(), from);
        int i = from;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.stripLeading().startsWith("at ")) {
                Frame f = Frames.fromLine(line, i + 1);
                if (f != null) {
                    b.frames.add(f);
                    b.lastLine = i + 1;
                    continue;
                }
            }
            Head cause = matchHead(line);
            if (cause != null) {
                if (cause.kind().equalsIgnoreCase("Caused by")) {
                    b.startCause(cause.className(), cause.message());
                    b.lastLine = i + 1;
                    continue;
                }
                if (cause.kind().equals("root")) {
                    // A brand new stack trace; do not swallow it into this one.
                    break;
                }
            }
            if (MORE.matcher(line).find()) {
                b.lastLine = i + 1;
                continue;
            }
            if (line.isBlank()) {
                // Blank line only ends the block if the next line is not another frame.
                if (i + 1 < lines.size() && isFrameLine(lines.get(i + 1))) {
                    continue;
                }
                i++;
                break;
            }
            break;
        }
        b.close();
        b.nextIndex = i;
        return b;
    }

    private static String levelOf(String line) {
        Matcher m = Pattern.compile("\\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|SEVERE)\\b").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The stack's own timestamp. Logback and log4j print the throwable on the line *after* the
     * dated message, so a header with no stamp on it borrows the nearest one above — otherwise
     * every Spring Boot log would parse as undated and EXC003 would never see the burst.
     */
    private static Long stampFor(List<String> lines, int headerIndex) {
        for (int back = 0; back <= 3 && headerIndex - back >= 0; back++) {
            String candidate = lines.get(headerIndex - back);
            if (candidate.stripLeading().startsWith("at ")) {
                continue;
            }
            Long ms = Epochs.fromLine(candidate);
            if (ms != null) {
                return ms;
            }
        }
        return null;
    }

    private static String timestampOf(List<String> lines, int headerIndex) {
        for (int back = 0; back <= 3 && headerIndex - back >= 0; back++) {
            String candidate = lines.get(headerIndex - back);
            Matcher m = STAMP_TEXT.matcher(candidate);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    private static final Pattern STAMP_TEXT = Pattern.compile(
            "\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:\\s*[+-]\\d{2}:?\\d{2}|Z)?");

    /** The literal stamp, so the report can show the time the reader's own logger printed. */
    private static String timestampOf(String line) {
        Matcher m = Pattern.compile("^\\s*\\[?\\s*(\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}"
                + "(?:[.,]\\d{1,9})?(?:\\s*[+-]\\d{2}:?\\d{2}|Z)?)").matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.strip();
    }
}
