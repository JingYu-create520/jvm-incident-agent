package dev.jingyu.jia.model;

import java.util.List;
import java.util.Locale;

/** A parsed {@code jmap -histo[:live]} table. */
public record Histo(TextSource source, boolean live, List<ClassStat> classes, long totalBytes, long totalInstances) {

    public static final String BYTE_ARRAY = "[B";
    public static final String CHAR_ARRAY = "[C";
    public static final String STRING = "java.lang.String";

    public double shareOf(ClassStat c) {
        return totalBytes <= 0 ? 0.0 : (double) c.bytes() / totalBytes;
    }

    public List<ClassStat> topByBytes(int n) {
        return classes.stream()
                .sorted((a, b) -> Long.compare(b.bytes(), a.bytes()))
                .limit(n)
                .toList();
    }

    public List<ClassStat> topByInstances(int n) {
        return classes.stream()
                .sorted((a, b) -> Long.compare(b.instances(), a.instances()))
                .limit(n)
                .toList();
    }

    /** Byte/char array + String dominance — the classic "you need a real heap dump" signal. */
    public double primitiveBagShare() {
        return totalBytes <= 0 ? 0.0 : (double) primitiveBagBytes() / totalBytes;
    }

    /** Absolute bytes in the {@code byte[] + char[] + String} bag; shares alone mislead on small heaps. */
    public long primitiveBagBytes() {
        long bag = 0;
        for (ClassStat c : classes) {
            String base = c.baseClass();
            if (BYTE_ARRAY.equals(base) || CHAR_ARRAY.equals(base) || STRING.equals(base)) {
                bag += c.bytes();
            }
        }
        return bag;
    }

    public List<ClassStat> businessClasses() {
        return classes.stream().filter(c -> !c.isJdk()).toList();
    }

    public boolean empty() {
        return classes.isEmpty();
    }

    public static String humanBytes(long b) {
        if (b < 1024) {
            return b + " B";
        }
        if (b < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        }
        if (b < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", b / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
    }
}
