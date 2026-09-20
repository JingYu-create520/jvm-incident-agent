package dev.jingyu.jia.parse;

import dev.jingyu.jia.model.Frame;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared parsing of a single stack frame, used by both the dump and the log parser. */
public final class Frames {

    /** {@code java.base@17.0.5/} or {@code java.base/} in front of a JDK frame's class. */
    private static final Pattern MODULE_PREFIX = Pattern.compile("^[\\w.$-]+@[\\w.+\\-]+/|^[\\w.$-]+//?");
    /**
     * A stack frame, with the two decorations real loggers append: logback's
     * {@code ~[jar:version]} suffix and JDK 9+ module prefixes on the class name.
     */
    private static final Pattern FRAME_LINE = Pattern.compile("^\\s*at\\s+(\\S+)\\((.*?)\\)\\s*(?:~\\[.*\\])?\\s*$");

    private Frames() {
    }

    /** Returns null when the line is not a stack frame. */
    public static Frame fromLine(String line, int lineNo) {
        Matcher m = FRAME_LINE.matcher(line);
        if (!m.matches()) {
            return null;
        }
        return parse(m.group(1), m.group(2), lineNo);
    }

    public static Frame parse(String qualifiedClassMethod, String paren, int lineNo) {
        String q = MODULE_PREFIX.matcher(qualifiedClassMethod.strip()).replaceFirst("");
        int dot = q.lastIndexOf('.');
        String cls = dot < 0 ? "<unknown>" : q.substring(0, dot);
        String method = dot < 0 ? q : q.substring(dot + 1);
        // jstack writes the module inside the brackets: …run(java.base@17.0.5/Thread.java:833)
        String p = MODULE_PREFIX.matcher(paren == null ? "" : paren.strip()).replaceFirst("");
        boolean nativeMethod = p.isEmpty()
                || p.equalsIgnoreCase("Native Method")
                || p.startsWith("Native Method");
        String file = null;
        Integer line = null;
        if (!nativeMethod) {
            int colon = p.indexOf(':');
            if (colon > 0) {
                file = p.substring(0, colon).strip();
                String rest = p.substring(colon + 1).replace(")", "").strip();
                try {
                    line = Integer.parseInt(rest);
                } catch (NumberFormatException ignored) {
                    line = null;
                }
            } else if (!p.equalsIgnoreCase("Unknown Source")
                    && !p.startsWith("Source File")
                    && !p.equalsIgnoreCase("Compiled Code")) {
                file = p;
            }
        }
        return new Frame(cls, method, file, line, nativeMethod, lineNo);
    }
}
