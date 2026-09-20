package dev.jingyu.jia;

import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.parse.TextFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Test utilities: literal fixtures from resources, plus authentic synthetic text builders. */
public final class Fixtures {

    private Fixtures() {
    }

    public static TextSource source(String name) {
        try {
            Path p = Path.of("src/test/resources/fixtures", name);
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("missing fixture " + p.toAbsolutePath());
            }
            return TextSource.of(name, TextFiles.readLines(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** jstack text in the shape JDK 11+ prints, with cpu/elapsed and module-qualified frames. */
    public static final class Dump {
        private final StringBuilder sb = new StringBuilder();
        private int index = 1;
        private int nidCounter = 0x1000;

        public Dump header() {
            sb.append("2026-09-20 21:00:00\n");
            sb.append("Full thread dump OpenJDK 64-Bit Server VM (17.0.5+8-b653.25 mixed mode, sharing):\n\n");
            return this;
        }

        /** A worker parked in the pool's own queue: idle, and therefore invisible to the rules. */
        public Dump idleWorker(String name, double cpuMs, double elapsedSec) {
            return thread(name, index++, nidCounter++, "WAITING (parking)", "waiting on condition", cpuMs,
                    elapsedSec, List.of(
                    "at jdk.internal.misc.Unsafe.park(java.base@17.0.5/Native Method)",
                    "- parking to wait for  <0x000000071ab80100> (a java.util.concurrent.locks"
                            + ".AbstractQueuedSynchronizer$ConditionObject)",
                    "at java.util.concurrent.locks.LockSupport.park(java.base@17.0.5/LockSupport.java:341)",
                    "at java.util.concurrent.SynchronousQueue$TransferStack.xfer(java.base@17.0.5"
                            + "/SynchronousQueue.java:939)",
                    "at java.util.concurrent.SynchronousQueue.poll(java.base@17.0.5/SynchronousQueue.java:900)",
                    "at java.util.concurrent.ThreadPoolExecutor.getTask(java.base@17.0.5"
                            + "/ThreadPoolExecutor.java:1062)",
                    "at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@17.0.5"
                            + "/ThreadPoolExecutor.java:1122)",
                    "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"), List.of());
        }

        public Dump runnable(String name, String businessFrame, double cpuMs, double elapsedSec) {
            return thread(name, index++, nidCounter++, "RUNNABLE", "runnable", cpuMs, elapsedSec,
                    List.of("at " + businessFrame,
                            "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"),
                    List.of());
        }

        public Dump sleeping(String name, int count, double elapsedSec) {
            for (int i = 1; i <= count; i++) {
                thread(name + "-" + i, index++, nidCounter++, "TIMED_WAITING (sleeping)", "sleeping",
                        5.0, elapsedSec, List.of(
                        "at java.lang.Thread.sleep(java.base@17.0.5/Native Method)",
                        "- parking to wait for  <0x000000071abc0000> (a java.lang.Thread)",
                        "at dev.jingyu.victim.LeakService$$Lambda$40/0x000001d8.lambda$run$0(LeakService.java:33)",
                        "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"), List.of());
            }
            return this;
        }

        /** BLOCKED entering {@code waitOn} while holding {@code hold}. */
        public Dump blocked(String name, String businessFrame, String hold, String waitOn) {
            List<String> lines = new ArrayList<>();
            lines.add("at " + businessFrame);
            if (waitOn != null) {
                lines.add("- waiting to lock <" + waitOn + "> (a java.lang.Object)");
            }
            if (hold != null) {
                lines.add("- locked <" + hold + "> (a java.lang.Object)");
            }
            lines.add("at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)");
            return thread(name, index++, nidCounter++, "BLOCKED (on object monitor)",
                    "waiting for monitor entry", 12.0, 30.0, lines, List.of());
        }

        public Dump socketBlocked(String name) {
            return thread(name, index++, nidCounter++, "RUNNABLE", "runnable", 12.0, 30.0, List.of(
                    "at java.net.SocketInputStream.socketRead0(java.base@17.0.5/Native Method)",
                    "at java.net.SocketInputStream.socketRead(java.base@17.0.5/SocketInputStream.java:115)",
                    "at dev.jingyu.victim.PaymentsClient.charge(PaymentsClient.java:64)",
                    "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"), List.of());
        }

        public Dump thread(String name, int idx, int nid, String state, String status, double cpuMs,
                           double elapsedSec, List<String> body, List<String> ownable) {
            sb.append('"').append(name).append("\" #").append(idx)
                    .append(cpuMs >= 0 ? " prio=5 os_prio=0 cpu=" + fmt(cpuMs) + "ms elapsed="
                            + fmt(elapsedSec) + "s" : " prio=5 os_prio=0")
                    .append(" tid=0x000001d8a").append(Integer.toHexString(0x1000 + idx))
                    .append(" nid=0x").append(Integer.toHexString(nid))
                    .append(" ").append(status).append(" [0x0000006b1f000000]\n");
            if (state != null) {
                sb.append("   java.lang.Thread.State: ").append(state).append('\n');
            }
            for (String line : body) {
                if (line.startsWith("- ")) {
                    sb.append("\t").append(line).append('\n');
                } else {
                    sb.append("\t").append(line).append('\n');
                }
            }
            sb.append("\n   Locked ownable synchronizers:\n");
            if (ownable.isEmpty()) {
                sb.append("\t- None\n");
            } else {
                ownable.forEach(a -> sb.append("\t- <").append(a).append("> (a java.util.concurrent.locks"
                        + ".ReentrantLock$NonfairSync)\n"));
            }
            sb.append('\n');
            return this;
        }

        /** The trailer jstack writes when it detects a monitor cycle itself. */
        public Dump jstackDeadlockTrailer(String a, String b, String objA, String objB) {
            sb.append("\nFound one Java-level deadlock:\n=============================\n");
            sb.append('"').append(a).append("\":\n  waiting to lock monitor 0x000001d8a3f0c800 (object ")
                    .append(objA).append(", a java.lang.Object),\n  which is held by \"").append(b)
                    .append("\"\n");
            sb.append('"').append(b).append("\":\n  waiting to lock monitor 0x000001d8a3f0d000 (object ")
                    .append(objB).append(", a java.lang.Object),\n  which is held by \"").append(a)
                    .append("\"\n\nJava stack information for the threads listed above:\n");
            sb.append("===================================================\n\nFound 1 deadlock.\n");
            return this;
        }

        public TextSource build(String fileName) {
            return TextSource.of(fileName, TextFiles.splitLines(sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        private static String fmt(double v) {
            return String.format(java.util.Locale.ROOT, "%.2f", v);
        }
    }

    public static Dump dump() {
        return new Dump().header();
    }

    /**
     * A GC log in JDK 9+ unified form. Each row is {@code [uptimeSec, heapBeforeMb, heapAfterMb,
     * pauseMs]}; the post-GC column is what the live-set rules read, so the two lists are kept
     * separate rather than encoded in a column.
     */
    public static String gcUnified(List<double[]> young, List<double[]> full, String collectorLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("[2026-09-20T21:00:00.100+0800][0.010s][info][gc] ")
                .append(collectorLine == null ? "Using G1" : collectorLine).append('\n');
        sb.append("[2026-09-20T21:00:00.101+0800][0.011s][info][gc,init] Version: 17.0.5+8-b653.25 (release)\n");
        sb.append("[2026-09-20T21:00:00.101+0800][0.011s][info][gc,init] Max Heap Size: 256M\n");

        record Row(double at, double before, double after, double pause, boolean full) {
        }
        List<Row> rows = new ArrayList<>();
        young.forEach(e -> rows.add(new Row(e[0], e[1], e[2], e.length > 3 ? e[3] : 1.0, false)));
        full.forEach(e -> rows.add(new Row(e[0], e[1], e[2], e.length > 3 ? e[3] : 100.0, true)));
        rows.sort(Comparator.comparingDouble(Row::at));

        int id = 0;
        for (Row r : rows) {
            sb.append("[2026-09-20T21:00:00.000+0800][")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", r.at())).append("s][info][gc] GC(")
                    .append(id++).append(") ")
                    .append(r.full() ? "Pause Full (G1 Compaction Pause) "
                            : "Pause Young (Normal) (G1 Evacuation Pause) ")
                    .append((long) r.before()).append("M->").append((long) r.after()).append("M(256M) ")
                    .append(String.format(java.util.Locale.ROOT, "%.3f", r.pause())).append("ms\n");
        }
        return sb.toString();
    }

    public static String histo(List<Object[]> rows, long totalBytes, long totalInstances) {
        StringBuilder sb = new StringBuilder();
        sb.append(" num     #instances         #bytes  class name (module)\n");
        sb.append("-------------------------------------------------------\n");
        int rank = 1;
        for (Object[] r : rows) {
            sb.append(String.format("  %3d:      %10d     %10d  %s (java.base@17.0.5)%n",
                    rank++, (Long) r[1], (Long) r[2], r[0]));
        }
        sb.append(String.format("Total      %10d     %10d%n", totalInstances, totalBytes));
        return sb.toString();
    }

    /** One logback-shaped exception block, with the {@code ~[jar:version]} suffix real logs carry. */
    public static String logbackException(String timestamp, String outer, String outerMsg, String root,
                                          String rootMsg, String businessFrame) {
        return timestamp + " ERROR 12345 --- [http-nio-8080-exec-3] d.j.v.FlakyService             : "
                + outer + ": " + outerMsg + "\n"
                + "java.lang.RuntimeException: " + outerMsg + "\n"
                + "\tat " + businessFrame + " ~[!/:1.0.0]\n"
                + "\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:883) ~[spring-web-6.1.6.jar:6.1.6]\n"
                + "\tat java.base/java.lang.Thread.run(Thread.java:833) ~[na:na]\n"
                + "Caused by: " + root + ": " + rootMsg + "\n"
                + "\tat " + businessFrame + " ~[!/:1.0.0]\n"
                + "\tat java.base/java.util.HashMap.forEach(HashMap.java:1421) ~[na:na]\n"
                + "\t... 27 more\n\n";
    }

    public static List<Path> corpus(String scenario) {
        Path dir = Path.of("corpus", scenario);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static boolean corpusAvailable() {
        return Files.isDirectory(Path.of("corpus"));
    }
}
