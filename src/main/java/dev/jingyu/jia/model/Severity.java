package dev.jingyu.jia.model;

/**
 * How much a finding should change what the reader does next.
 *
 * <p>{@link #INFO} is reserved for degraded input (unparsed file, missing decorator,
 * unknown JDK format) so a report never stays silent about what it could not look at.
 */
public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    public boolean atLeast(Severity other) {
        return ordinal() <= other.ordinal();
    }
}
