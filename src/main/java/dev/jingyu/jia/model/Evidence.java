package dev.jingyu.jia.model;

/**
 * A single quotable fact: which file, which lines, what they say.
 *
 * <p>Every {@link Finding} carries at least one of these. That is the product
 * promise — a reader who distrusts a conclusion can open the file at the line
 * and check it by hand.
 */
public record Evidence(String file, int startLine, int endLine, String quote, String note) {

    public static Evidence of(TextSource src, int line) {
        return new Evidence(src.name(), line, line, src.quote(line, 200), null);
    }

    public static Evidence of(TextSource src, int line, String note) {
        return new Evidence(src.name(), line, line, src.quote(line, 200), note);
    }

    public static Evidence range(TextSource src, int from, int to) {
        return new Evidence(src.name(), from, to, src.quote(from, 200), null);
    }

    public static Evidence range(TextSource src, int from, int to, String note) {
        return new Evidence(src.name(), from, to, src.quote(from, 200), note);
    }
}
