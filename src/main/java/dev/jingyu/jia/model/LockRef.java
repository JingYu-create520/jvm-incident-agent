package dev.jingyu.jia.model;

/**
 * A monitor / synchronizer mention inside a stack, e.g.
 * {@code - waiting to lock <0x000000071ab00000> (a java.lang.Object)}.
 */
public record LockRef(Kind kind, String address, String className, int line) {

    public enum Kind {
        /** {@code - locked <addr>} — this thread owns the monitor. */
        HELD,
        /** {@code - waiting to lock <addr>} — blocked entering a synchronized block. */
        WAITING_TO_LOCK,
        /** {@code - waiting on <addr>} — parked inside Object.wait(). */
        WAITING_ON,
        /** {@code - parking to wait for <addr>} — java.util.concurrent lock. */
        PARKING,
        /** {@code - waiting to re-lock in wait() <addr>}. */
        RE_LOCK_AFTER_WAIT,
        /** Entry under "Locked ownable synchronizers:" (jstack -l). */
        OWNABLE_HELD
    }
}
