package dev.jingyu.jia.analyze.rules;

import dev.jingyu.jia.model.JThread;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The two noise sources that would otherwise make every thread rule cry wolf.
 *
 * <p>1. JVM housekeeping threads ({@code GC Thread#3}, {@code C2 CompilerThread1}) are
 * blocked, parked and CPU-hungry by design. 2. An idle worker pool — 200 Tomcat threads
 * all sitting in {@code LinkedBlockingQueue.poll} — is what a healthy server looks like
 * at 4 a.m., and it is the single biggest false-positive trap in thread-dump analysis.
 */
public final class ThreadNoise {

    private static final Pattern JVM_INTERNAL_NAME = Pattern.compile(
            "^(VM Thread|VM Periodic Task Thread|Reference Handler|Finalizer|Signal Dispatcher"
                    + "|Attach Listener|Notification Thread|DestroyJavaVM|process reaper"
                    + "|Common-Cleaner|Service Thread|Sweeper thread"
                    + "|GC Thread#?\\d*|G1 Main Marker|G1 Conc#?\\d*|G1 Refine#?\\d*|G1 Young RemSet Sampling"
                    + "|GC Thread Group|Concurrent Mark Thread|ZGC (Mark|Relocate|Polling|Main) Thread"
                    + "|Shenandoah|C[12] CompilerThread\\d*|JVMCI CompilerThread\\d*|CompilerThread\\d*"
                    + "|Scratch|Task\\d+|pool-\\d+-finalizer).*$", Pattern.CASE_INSENSITIVE);

    /** Frames that mean "this thread is waiting for work", not "this thread is stuck". */
    private static final List<String> IDLE_FRAMES = List.of(
            "java.util.concurrent.SynchronousQueue#poll",
            "java.util.concurrent.SynchronousQueue#take",
            "java.util.concurrent.SynchronousQueue$TransferStack#xfer",
            "java.util.concurrent.LinkedBlockingQueue#take",
            "java.util.concurrent.LinkedBlockingQueue#poll",
            "java.util.concurrent.ArrayBlockingQueue#take",
            "java.util.concurrent.ArrayBlockingQueue#poll",
            "java.util.concurrent.ThreadPoolExecutor#getTask",
            "java.util.concurrent.ThreadPoolExecutor#runWorker",
            "java.util.concurrent.DelayQueue#take",
            "jdk.internal.misc.Unsafe#park",
            "sun.misc.Unsafe#park",
            "java.lang.Object#wait",
            "java.util.concurrent.locks.LockSupport#park",
            "java.lang.ref.ReferenceQueue#remove",
            "io.netty.util.concurrent.SingleThreadEventExecutor#takeTask",
            "io.netty.util.concurrent.SingleThreadEventExecutor#nextTask",
            "org.apache.tomcat.util.threads.TaskQueue#take",
            "org.apache.tomcat.util.threads.TaskThread$WrappingRunnable#run");

    /** Where a native socket read shows up: RUNNABLE, but really waiting on the peer. */
    private static final List<String> SOCKET_FRAMES = List.of(
            "java.net.SocketInputStream#socketRead0",
            "java.net.SocketInputStream#socketRead",
            "sun.nio.ch.Net#poll",
            "sun.nio.ch.EPoll#wait",
            "sun.nio.ch.EPollArrayWrapper#epollWait",
            "sun.nio.ch.KQueueArrayWrapper#kevent0",
            "java.net.PlainSocketImpl#socketAccept",
            "java.net.DatagramSocket#receive",
            "java.net.Socket#accept");

    private ThreadNoise() {
    }

    public static boolean jvmInternal(JThread t) {
        return jvmInternalName(t.name());
    }

    public static boolean jvmInternalName(String name) {
        return name != null && JVM_INTERNAL_NAME.matcher(name.strip()).matches();
    }

    public static boolean idleWorker(JThread t) {
        if (t.state() == JThread.ThreadState.RUNNABLE || t.blocked()) {
            return false;
        }
        return matchesWithin(t, IDLE_FRAMES, 6);
    }

    /**
     * A thread that is BLOCKED, or parked somewhere other than the pool's work queue,
     * is doing something (or waiting on something) — that is what the rules care about.
     */
    public static boolean activeOrStuck(JThread t) {
        return !idleWorker(t) && t.state() != JThread.ThreadState.TERMINATED;
    }

    /** A thread in {@code Thread.sleep} is waiting on a clock, not on a contended resource. */
    public static boolean sleeping(JThread t) {
        return t.state() == JThread.ThreadState.TIMED_WAITING
                && t.topFrame().map(f -> "java.lang.Thread#sleep".equals(f.id())).orElse(false);
    }

    public static boolean socketBlocked(JThread t) {
        return t.state() == JThread.ThreadState.RUNNABLE && matchesWithin(t, SOCKET_FRAMES, 6);
    }

    private static boolean matchesWithin(JThread t, List<String> ids, int depth) {
        List<dev.jingyu.jia.model.Frame> frames = t.stack();
        int n = Math.min(frames.size(), depth);
        for (int i = 0; i < n; i++) {
            String id = frames.get(i).id().toLowerCase(Locale.ROOT);
            for (String needle : ids) {
                if (id.endsWith(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        if (frames.isEmpty()) {
            String status = t.waitStatus() == null ? "" : t.waitStatus().toLowerCase(Locale.ROOT);
            return status.contains("object.wait");
        }
        return false;
    }
}
