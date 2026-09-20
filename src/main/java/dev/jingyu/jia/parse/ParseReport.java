package dev.jingyu.jia.parse;

import java.util.List;

/**
 * A parse result plus human-readable notes about what could not be understood.
 *
 * <p>Parsers never throw on malformed input: a rule that sees partial data still
 * produces a partial report, and the notes surface as INFO findings so the report
 * is honest about its own blind spots.
 */
public record ParseReport<T>(T value, List<String> notes) {

    public static <T> ParseReport<T> ok(T value) {
        return new ParseReport<>(value, List.of());
    }

    public static <T> ParseReport<T> of(T value, List<String> notes) {
        return new ParseReport<>(value, List.copyOf(notes));
    }

    public ParseReport<T> withNote(String note) {
        List<String> merged = new java.util.ArrayList<>(notes);
        merged.add(note);
        return new ParseReport<>(value, List.copyOf(merged));
    }

    public boolean clean() {
        return notes.isEmpty();
    }
}
