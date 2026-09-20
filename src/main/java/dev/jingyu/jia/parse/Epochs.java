package dev.jingyu.jia.parse;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timestamp lifting from other people's log formats.
 *
 * <p>An offset in the pattern is honoured; a bare local time is read in the
 * analyzer's default zone. The incident happened on the server, so GC and
 * exception stamps only become comparable when at least one of them carries an
 * offset or both come from the same machine.
 */
public final class Epochs {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ISO_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"),
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss.SSS ZZZZ"),
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH));

    private static final Pattern STAMP_IN_TEXT = Pattern.compile(
            "\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?"
                    + "|\\d{2}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{1,9}"
                    + "|\\d{1,2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?: [A-Z]{2,5})?");

    private Epochs() {
    }

    /** Millis since epoch, or null when the token holds no recognisable timestamp. */
    public static Long toMillis(String token) {
        if (token == null) {
            return null;
        }
        String s = token.replace('[', ' ').replace(']', ' ').replace("  ", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter f : FORMATS) {
            try {
                TemporalAccessor ta = f.parseBest(s,
                        Instant::from, OffsetDateTime::from, ZonedDateTime::from, LocalDateTime::from);
                return fromParsed(ta);
            } catch (DateTimeParseException | java.time.temporal.UnsupportedTemporalTypeException e) {
                // try the next shape
            } catch (RuntimeException e) {
                // a formatter that wants a field this stamp does not carry — keep looking
            }
        }
        return null;
    }

    /** Pull the first timestamp out of a longer line, e.g. a log4j or logback prefix. */
    public static Long fromLine(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = STAMP_IN_TEXT.matcher(line);
        return m.find() ? toMillis(m.group()) : null;
    }

    /** {@code yyyy-MM-dd HH:mm:ss.SSS} in the given zone, for report timelines. */
    public static String format(Long millis, ZoneId zone) {
        if (millis == null) {
            return "";
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(zone)
                .format(Instant.ofEpochMilli(millis));
    }

    private static Long fromParsed(TemporalAccessor ta) {
        if (ta.isSupported(java.time.temporal.ChronoField.EPOCH_DAY)
                && ta instanceof OffsetDateTime o) {
            return o.toInstant().toEpochMilli();
        }
        if (ta instanceof ZonedDateTime z) {
            return z.toInstant().toEpochMilli();
        }
        if (ta instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (ta instanceof LocalDateTime l) {
            return l.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return null;
    }
}
