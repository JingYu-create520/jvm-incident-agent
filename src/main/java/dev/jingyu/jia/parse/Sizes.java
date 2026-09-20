package dev.jingyu.jia.parse;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code 256M} / {@code 65536K} / {@code 128.0M} / {@code 0.0B} → bytes. */
public final class Sizes {

    private static final Pattern SIZE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*([KMGT]?B?)$", Pattern.CASE_INSENSITIVE);

    private Sizes() {
    }

    public static Long parseBytes(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().replace(",", "");
        if (s.isEmpty() || s.equals("-") || s.equalsIgnoreCase("unknown")) {
            return null;
        }
        Matcher m = SIZE.matcher(s.toUpperCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        double n = Double.parseDouble(m.group(1));
        String unit = m.group(2);
        long mult = switch (unit) {
            case "K", "KB" -> 1024L;
            case "M", "MB" -> 1024L * 1024L;
            case "G", "GB" -> 1024L * 1024L * 1024L;
            case "T", "TB" -> 1024L * 1024L * 1024L * 1024L;
            default -> 1L;
        };
        return (long) (n * mult);
    }

    public static double parseMs(String raw) {
        return Double.parseDouble(raw);
    }

    public static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0fM", bytes / 1048576.0);
    }
}
