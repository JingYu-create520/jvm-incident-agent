package dev.jingyu.jia.model;

import java.util.List;

/**
 * A parsed artifact keeps the raw text it came from so every finding can quote
 * the exact lines it was derived from. Line numbers are 1-based.
 */
public record TextSource(String name, List<String> lines) {

    public static TextSource of(String name, List<String> lines) {
        return new TextSource(name, List.copyOf(lines));
    }

    public int size() {
        return lines.size();
    }

    public String line(int oneBased) {
        int i = oneBased - 1;
        if (i < 0 || i >= lines.size()) {
            return "";
        }
        return lines.get(i);
    }

    /** Trimmed, bounded quote for report output. */
    public String quote(int oneBased, int maxChars) {
        String raw = line(oneBased).stripTrailing();
        if (raw.length() <= maxChars) {
            return raw;
        }
        return raw.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /** Inclusive block of lines, with trailing blanks removed. */
    public List<String> block(int fromLine, int toLine) {
        int from = Math.max(1, Math.min(fromLine, lines.size() + 1));
        int to = Math.max(from - 1, Math.min(toLine, lines.size()));
        List<String> out = new java.util.ArrayList<>(lines.subList(from - 1, to));
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
