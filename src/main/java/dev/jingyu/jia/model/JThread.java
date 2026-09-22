package dev.jingyu.jia.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A parsed thread from a jstack-style dump. */
public record JThread(String name,
                      Integer index,
                      Integer prio,
                      Long osPrio,
                      String tid,
                      String nid,
                      Double cpuMillis,
                      Double elapsedSeconds,
                      ThreadState state,
                      String waitStatus,
                      List<Frame> stack,
                      List<LockRef> locks,
                      List<LockRef> ownableSynchronizers,
                      int startLine,
                      int endLine) {

    /**
     * A header line quoted like a thread, with no frames, no monitors and no ownable synchronizers
     * behind it, is a VM worker rather than a Java thread. ZGC prints {@code "ZWorker#0" … runnable}
     * and {@code "RuntimeWorker#3" … runnable} exactly this way, and how many of them exist tracks
     * cores and heap size, not anything the application does — so no rule that groups by name
     * family or claims "these all sit in the same frame" may include them.
     *
     * <p>Their trailing {@code runnable} is HotSpot's OS-level status word, which the parser reads
     * into {@code RUNNABLE} like any other thread's; that is why the absence of a stack, and not
     * the state, is what separates them.
     */
    public boolean isVmWorker() {
        return stack.isEmpty() && locks.isEmpty() && ownableSynchronizers.isEmpty();
    }

    public enum ThreadState {
        RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NEW, TERMINATED, UNKNOWN;

        public static ThreadState parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "RUNNABLE" -> {
                    return RUNNABLE;
                }
                case "BLOCKED" -> {
                    return BLOCKED;
                }
                case "WAITING" -> {
                    return WAITING;
                }
                case "TIMED_WAITING" -> {
                    return TIMED_WAITING;
                }
                case "NEW" -> {
                    return NEW;
                }
                case "TERMINATED" -> {
                    return TERMINATED;
                }
                default -> {
                    return UNKNOWN;
                }
            }
        }
    }

    public boolean blocked() {
        return state == ThreadState.BLOCKED;
    }

    public boolean waiting() {
        return state == ThreadState.WAITING || state == ThreadState.TIMED_WAITING;
    }

    /** A thread that is neither running business code nor finished. */
    public boolean parked() {
        return waiting();
    }

    public Optional<Frame> topFrame() {
        return stack.stream().filter(f -> !"<no such file>".equals(f.declaringClass())).findFirst();
    }

    public List<LockRef> locksOfKind(LockRef.Kind kind) {
        return locks.stream().filter(l -> l.kind() == kind).toList();
    }

    /** Monitor this thread is queued on, if any. */
    public Optional<LockRef> waitingMonitor() {
        List<LockRef> w = locksOfKind(LockRef.Kind.WAITING_TO_LOCK);
        if (!w.isEmpty()) {
            return Optional.of(w.get(w.size() - 1));
        }
        List<LockRef> r = locksOfKind(LockRef.Kind.RE_LOCK_AFTER_WAIT);
        if (!r.isEmpty()) {
            return Optional.of(r.get(r.size() - 1));
        }
        List<LockRef> p = locksOfKind(LockRef.Kind.PARKING);
        return p.isEmpty() ? Optional.empty() : Optional.of(p.get(p.size() - 1));
    }

    /** Monitors this thread owns. */
    public List<LockRef> heldMonitors() {
        return locksOfKind(LockRef.Kind.HELD);
    }

    public List<LockRef> heldSynchronizers() {
        List<LockRef> out = new java.util.ArrayList<>(locksOfKind(LockRef.Kind.OWNABLE_HELD));
        out.addAll(locksOfKind(LockRef.Kind.HELD));
        return out;
    }

    /** First frame that is plausibly the caller's own code. */
    public Optional<Frame> topBusinessFrame() {
        return stack.stream().filter(Frame::isBusiness).findFirst();
    }

    public String stateLabel() {
        return state == ThreadState.UNKNOWN && waitStatus != null ? waitStatus : state.name();
    }
}
