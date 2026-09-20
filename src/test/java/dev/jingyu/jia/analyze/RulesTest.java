package dev.jingyu.jia.analyze;

import dev.jingyu.jia.Fixtures;
import dev.jingyu.jia.model.Config;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.Finding;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.Severity;
import dev.jingyu.jia.model.Snapshot;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.model.ThreadDump;
import dev.jingyu.jia.parse.HistoParser;
import dev.jingyu.jia.parse.StackParser;
import dev.jingyu.jia.parse.TextFiles;
import dev.jingyu.jia.parse.ThreadDumpParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule gets a positive case (the incident is present and named) and a negative case
 * (the healthy equivalent produces nothing). The negative half is the contract the tool is
 * judged on in production, so it is not optional coverage.
 */
@DisplayName("rules")
class RulesTest {

    private static final Config CFG = Config.defaults();

    private static Snapshot snapshotOf(TextSource src) {
        List<ThreadDump> dumps = ThreadDumpParser.parse(src).value();
        Snapshot.Builder b = Snapshot.builder();
        dumps.forEach(b::addDump);
        return b.build();
    }

    private static Snapshot withGc(String text) {
        return withGc(TextSource.of("gc.log",
                TextFiles.splitLines(text, java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static Snapshot withGc(TextSource src) {
        return Snapshot.builder().gcLog(dev.jingyu.jia.parse.GcLogParser.parse(src).value()).build();
    }

    private static Snapshot withHisto(TextSource src) {
        return Snapshot.builder().histo(HistoParser.parse(src).value()).build();
    }

    private static Snapshot withStacks(TextSource src) {
        List<ExceptionOccurrence> found = StackParser.parse(src).value();
        Snapshot.Builder b = Snapshot.builder();
        found.forEach(b::addException);
        return b.build();
    }

    private static List<Finding> run(Rule rule, Snapshot s) {
        return rule.evaluate(s, CFG);
    }

    private static List<Finding> run(Rule rule, Supplier<TextSource> src) {
        return run(rule, snapshotOf(src.get()));
    }

    private static List<Finding> run(Rule rule, TextSource src) {
        return run(rule, snapshotOf(src));
    }

    private static Rule rule(String id) {
        return Rules.byId(id).orElseThrow(() -> new AssertionError("rule " + id + " is not registered"));
    }

    /**
     * Expected rule ids, in any order. An argument containing a space is read as the failure
     * message instead — rule ids never have spaces, and this keeps the negative cases readable.
     */
    private static void assertIds(List<Finding> findings, String... expected) {
        java.util.List<String> want = new java.util.ArrayList<>();
        String message = "expected only these rule ids";
        for (String e : expected) {
            if (e.indexOf(' ') >= 0) {
                message = e;
            } else {
                want.add(e);
            }
        }
        java.util.Collections.sort(want);
        List<String> got = findings.stream().map(Finding::ruleId).distinct().sorted().toList();
        final String msg = message;
        assertEquals(want, got, () -> msg + " — summaries were: "
                + findings.stream().map(f -> f.ruleId() + " " + f.summary()).toList());
    }

    /** A busy-but-healthy JVM: idle pool, one working thread, no queues, no cycles. */
    private static TextSource healthyDump() {
        return Fixtures.dump()
                .idleWorker("main", 40, 900)
                .idleWorker("http-nio-8080-exec-1", 90, 880)
                .idleWorker("http-nio-8080-exec-2", 88, 880)
                .idleWorker("http-nio-8080-exec-3", 77, 880)
                .idleWorker("http-nio-8080-exec-4", 61, 880)
                .idleWorker("http-nio-8080-exec-5", 54, 880)
                .idleWorker("http-nio-8080-exec-6", 48, 880)
                .runnable("http-nio-8080-exec-7",
                        "com.example.orders.OrderRepository.findRecent(OrderRepository.java:64)", 1240, 860)
                .runnable("http-nio-8080-exec-8",
                        "com.example.orders.PriceCalculator.totalWithTax(PriceCalculator.java:88)", 1180, 860)
                .build("healthy.jstack");
    }

    @Nested
    @DisplayName("thread dump rules")
    class Tda {

        @Test
        @DisplayName("TDA001 finds the cycle the JVM also reports")
        void deadlockPositive() {
            var findings = run(rule("TDA001"), Fixtures.source("jdk17-deadlock.jstack"));
            assertEquals(1, findings.size());
            Finding f = findings.get(0);
            assertEquals(Severity.CRITICAL, f.severity());
            assertTrue(f.confidence() > 0.9, "jstack's own trailer corroborates");
            assertTrue(f.summary().contains("transfer-worker-1"));
            assertTrue(f.summary().contains("transfer-worker-2"));
            assertFalse(f.evidence().isEmpty());
        }

        @Test
        @DisplayName("TDA001 finds a ReentrantLock cycle that jstack does not report")
        void deadlockInvisibleToJvm() {
            String a = "0x000000071aa00001";
            String b = "0x000000071aa00002";
            var src = Fixtures.dump()
                    .idleWorker("main", 10, 100)
                    .thread("lock-holder-1", 20, 0x2001, "WAITING (parking)", "waiting on condition", 10, 100,
                            List.of("at jdk.internal.misc.Unsafe.park(java.base@17.0.5/Native Method)",
                                    "- parking to wait for  <" + b + "> (a java.util.concurrent.locks"
                                            + ".ReentrantLock$FairSync)",
                                    "at com.example.A.go(A.java:10)",
                                    "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"),
                            List.of(a))
                    .thread("lock-holder-2", 21, 0x2002, "WAITING (parking)", "waiting on condition", 10, 100,
                            List.of("at jdk.internal.misc.Unsafe.park(java.base@17.0.5/Native Method)",
                                    "- parking to wait for  <" + a + "> (a java.util.concurrent.locks"
                                            + ".ReentrantLock$FairSync)",
                                    "at com.example.B.go(B.java:10)",
                                    "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"),
                            List.of(b))
                    .build("jstack.txt");
            var findings = run(rule("TDA001"), src);
            assertEquals(1, findings.size(), "an executor deadlock must still be found");
            assertTrue(findings.get(0).confidence() < 0.99, "no jstack trailer, so slightly less certain");
        }

        @Test
        @DisplayName("TDA001 stays silent on a healthy dump")
        void deadlockNegative() {
            assertIds(run(rule("TDA001"), RulesTest::healthyDump));
        }

        @Test
        @DisplayName("TDA002 names the monitor, the holder and the queue length")
        void contentionPositive() {
            Fixtures.Dump d = Fixtures.dump().idleWorker("main", 10, 100);
            d.runnable("holder", "com.example.Cache.flush(Cache.java:220)", 500, 400);
            for (int i = 1; i <= 6; i++) {
                d.blocked("worker-" + i, "com.example.Cache.get(Cache.java:198)", null, "0x000000071ac00000");
            }
            d.thread("holder", 30, 0x3000, "RUNNABLE", "runnable", 500, 400, List.of(
                    "at com.example.Cache.flush(Cache.java:220)",
                    "- locked <0x000000071ac00000> (a java.util.LinkedHashMap)",
                    "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"), List.of());
            var findings = run(rule("TDA002"), d.build("t.dump"));
            assertFalse(findings.isEmpty());
            Finding f = findings.get(0);
            assertTrue(f.summary().contains("6 threads are queued"));
            assertEquals("0x000000071ac00000", f.metrics().get("monitor"));
            assertEquals("holder", f.metrics().get("holder"));
        }

        @Test
        @DisplayName("TDA002 ignores one thread waiting on one lock")
        void contentionNegative() {
            var src = Fixtures.dump()
                    .idleWorker("main", 10, 100)
                    .blocked("worker-1", "com.example.Cache.get(Cache.java:198)", null, "0x000000071ac00000")
                    .thread("owner", 21, 0x21, "RUNNABLE", "runnable", 5, 100, List.of(
                            "at com.example.Cache.put(Cache.java:201)",
                            "- locked <0x000000071ac00000> (a java.util.LinkedHashMap)",
                            "at java.lang.Thread.run(java.base@17.0.5/Thread.java:833)"), List.of())
                    .build("t.dump");
            assertIds(run(rule("TDA002"), src));
        }

        @Test
        @DisplayName("TDA003 fires on an oversized family and on growth between dumps")
        void threadLeak() {
            var big = Fixtures.dump().idleWorker("main", 10, 900).sleeping("victim-worker", 45, 500)
                    .build("threads.dump");
            var findings = run(rule("TDA003"), big);
            assertFalse(findings.isEmpty(), "45 same-name threads is a leak");
            assertEquals(45, findings.get(0).metrics().get("count"));

            Snapshot two = Snapshot.builder()
                    .addDump(ThreadDumpParser.parse(Fixtures.dump()
                            .sleeping("victim-worker", 42, 500).build("threads.dump")).value().get(0))
                    .addDump(ThreadDumpParser.parse(Fixtures.dump()
                            .sleeping("victim-worker", 60, 560).build("threads-2.dump")).value().get(0))
                    .build();
            var grown = rule("TDA003").evaluate(two, CFG);
            assertTrue(grown.stream().anyMatch(f -> "last".equals(String.valueOf(f.metrics().get("family")))
                            || f.metrics().containsKey("growthRatio")),
                    () -> "expected a growth-based finding, got " + grown);
        }

        @Test
        @DisplayName("TDA003 gives a servlet container room to be busy")
        void threadLeakNegative() {
            Fixtures.Dump d = Fixtures.dump().idleWorker("main", 10, 900);
            for (int i = 1; i <= 60; i++) {
                d.idleWorker("http-nio-8080-exec-" + i, 20 + i, 900);
            }
            assertIds(run(rule("TDA003"), d.build("threads.dump")),
                    "60 idle container workers are below the 200 bar for known pools");
        }

        @Test
        @DisplayName("TDA004 reports a crowd in one place, and RUNNABLE threads stuck on sockets")
        void blockedStack() {
            Fixtures.Dump crowd = Fixtures.dump().idleWorker("main", 10, 100);
            for (int i = 1; i <= 7; i++) {
                crowd.blocked("worker-" + i, "com.example.Queue.take(Queue.java:55)", null,
                        "0x000000071ad0000" + i);
            }
            var findings = run(rule("TDA004"), crowd.build("t.dump"));
            assertFalse(findings.isEmpty());
            assertEquals("stack-cluster", findings.get(0).metrics().get("variant"));

            Fixtures.Dump socks = Fixtures.dump().idleWorker("main", 10, 100);
            for (int i = 1; i <= 6; i++) {
                socks.socketBlocked("db-client-" + i);
            }
            var socket = run(rule("TDA004"), socks.build("t.dump"));
            assertTrue(socket.stream().anyMatch(f -> "socket-io".equals(f.metrics().get("variant"))),
                    () -> "expected the socket variant, got " + socket);
        }

        @Test
        @DisplayName("TDA004 does not mistake an idle pool for a hotspot")
        void blockedStackNegative() {
            assertIds(run(rule("TDA004"), RulesTest::healthyDump));
            Fixtures.Dump sleeping = Fixtures.dump().idleWorker("main", 10, 900)
                    .sleeping("retry-loop", 12, 900);
            assertIds(run(rule("TDA004"), sleeping.build("t.dump")),
                    "sleeping threads are a thread-count question, not a contention one");
        }

        @Test
        @DisplayName("TDA005 fires only when no worker is left idle")
        void poolStarvation() {
            Fixtures.Dump d = Fixtures.dump().idleWorker("main", 10, 900);
            for (int i = 1; i <= 8; i++) {
                d.blocked("report-fetcher-" + i, "com.example.Report.fetch(Report.java:44)", null,
                        "0x000000071ae00000");
            }
            var findings = run(rule("TDA005"), d.build("t.dump"));
            assertEquals(1, findings.size());
            assertEquals(8, findings.get(0).metrics().get("size"));

            Fixtures.Dump half = Fixtures.dump().idleWorker("main", 10, 900);
            for (int i = 1; i <= 4; i++) {
                half.idleWorker("report-fetcher-" + i, 20, 900);
            }
            for (int i = 5; i <= 8; i++) {
                half.blocked("report-fetcher-" + i, "com.example.Report.fetch(Report.java:44)", null,
                        "0x000000071ae00000");
            }
            assertIds(run(rule("TDA005"), half.build("t.dump")), "half an idle pool is normal capacity");
        }

        @Test
        @DisplayName("TDA006 measures cores from cpu/elapsed, using the delta when two dumps exist")
        void cpuHot() {
            var single = Fixtures.dump().idleWorker("main", 10, 900)
                    .runnable("calc-1", "com.example.TaxLoop.compute(TaxLoop.java:31)", 60_000, 90);
            var findings = run(rule("TDA006"), single.build("threads.dump"));
            assertEquals(1, findings.size());
            assertEquals("lifetime-average", findings.get(0).metrics().get("basis"));

            Snapshot two = Snapshot.builder()
                    .addDump(ThreadDumpParser.parse(Fixtures.dump()
                            .runnable("calc-1", "com.example.TaxLoop.compute(TaxLoop.java:31)", 100, 10)
                            .build("threads.dump")).value().get(0))
                    .addDump(ThreadDumpParser.parse(Fixtures.dump()
                            .runnable("calc-1", "com.example.TaxLoop.compute(TaxLoop.java:31)", 9_900, 20)
                            .build("threads-2.dump")).value().get(0))
                    .build();
            var delta = rule("TDA006").evaluate(two, CFG);
            assertEquals(1, delta.size());
            assertEquals("delta", delta.get(0).metrics().get("basis"));
            assertTrue(Double.parseDouble(String.valueOf(delta.get(0).metrics().get("cores"))) > 0.9,
                    () -> "expected ~0.98 cores, got " + delta.get(0).metrics().get("cores"));
        }

        @Test
        @DisplayName("TDA006 ignores JVM housekeeping and threads with no cpu column at all")
        void cpuNegative() {
            assertIds(run(rule("TDA006"), RulesTest::healthyDump));
            var noCpu = Fixtures.dump().thread("spinner", 5, 0x55, "RUNNABLE", "runnable", -1, -1,
                    List.of("at com.example.Loop.spin(Loop.java:9)"), List.of()).build("t.dump");
            assertIds(run(rule("TDA006"), noCpu), "a JDK 8 dump without cpu= must not guess");
        }
    }

    @Nested
    @DisplayName("GC rules")
    class Gc {

        @Test
        @DisplayName("GCA001 counts the densest window, not the whole log")
        void fullGcFrequency() {
            var log = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}),
                    List.of(new double[]{10, 250, 120, 90}, new double[]{20, 250, 122, 95},
                            new double[]{28, 250, 125, 88}), null));
            assertIds(run(rule("GCA001"), log), "GCA001");
            var calm = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}, new double[]{60, 60, 20, 5}),
                    List.of(new double[]{10, 250, 120, 90}, new double[]{600, 250, 121, 95}), null));
            assertIds(run(rule("GCA001"), calm), "two Full GCs hours apart are not a storm");
        }

        @Test
        @DisplayName("GCA002 compares p99 with the stated SLA")
        void longPauses() {
            var log = withGc(Fixtures.source("gc-jdk17-storm.log"));
            var findings = run(rule("GCA002"), log);
            assertEquals(1, findings.size());
            assertTrue(Double.parseDouble(String.valueOf(findings.get(0).metrics().get("maxMs"))) > 2000);
            var calm = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}, new double[]{2, 60, 21, 6}, new double[]{3, 60, 20, 4}),
                    List.of(), null));
            assertIds(run(rule("GCA002"), calm));
        }

        @Test
        @DisplayName("GCA003 sees both a climbing live set and one pinned at the ceiling")
        void heapLeakFingerprint() {
            var climbing = withGc(Fixtures.gcUnified(List.of(new double[]{1, 60, 20, 5}),
                    List.of(new double[]{10, 200, 100, 90}, new double[]{40, 230, 140, 95},
                            new double[]{70, 240, 190, 99}), null));
            assertEquals(1, run(rule("GCA003"), climbing).size());

            var plateau = withGc(Fixtures.gcUnified(List.of(new double[]{1, 200, 60, 5}),
                    List.of(new double[]{10, 255, 250, 90}, new double[]{20, 255, 251, 95},
                            new double[]{30, 255, 252, 99}, new double[]{40, 255, 252, 99}), null));
            var pinned = run(rule("GCA003"), plateau);
            assertEquals(1, pinned.size(), "a flat line at 98% of capacity is the same leak, later");
            assertEquals(Severity.CRITICAL, pinned.get(0).severity());

            var healthy = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}, new double[]{5, 70, 21, 6}),
                    List.of(new double[]{10, 200, 40, 90}, new double[]{60, 200, 41, 88},
                            new double[]{120, 200, 40, 92}), null));
            assertIds(run(rule("GCA003"), healthy), "a live set that returns to the same floor is not leaking");
        }

        @Test
        @DisplayName("GCA004 reads the collector's own promotion words")
        void prematurePromotion() {
            var log = withGc(Fixtures.source("gc-jdk17-storm.log"));
            var findings = run(rule("GCA004"), log);
            assertEquals(1, findings.size());
            assertEquals("G1", findings.get(0).metrics().get("collector"));
            assertTrue((int) findings.get(0).metrics().get("toSpaceExhausted") >= 1);
        }

        @Test
        @DisplayName("GCA004 stays quiet on a short, ordinary log")
        void prematurePromotionNegative() {
            var calm = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}, new double[]{4, 70, 22, 6}), List.of(), null));
            assertIds(run(rule("GCA004"), calm));
        }

        @Test
        @DisplayName("GCA005 reports metaspace pressure only after the JVM has settled")
        void configSmells() {
            var log = withGc(Fixtures.source("gc-jdk8-parallel.log"));
            var findings = run(rule("GCA005"), log);
            assertTrue(findings.stream().anyMatch(f -> f.summary().contains("metaspace")),
                    () -> "recurring Metadata GC Threshold collections should be flagged: " + findings);
            var calm = withGc(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 20, 5}, new double[]{4, 70, 22, 6}), List.of(), null));
            assertIds(run(rule("GCA005"), calm));
        }

        @Test
        @DisplayName("GCA006 computes pause share of wall time")
        void throughput() {
            var log = withGc(Fixtures.source("gc-jdk17-storm.log"));
            assertEquals(1, run(rule("GCA006"), log).size());
            var calm = withGc(Fixtures.gcUnified(java.util.stream.IntStream.range(0, 30)
                    .mapToObj(i -> new double[]{i * 2.0, 60, 20, 1.0}).toList(), List.of(), null));
            assertIds(run(rule("GCA006"), calm));
        }
    }

    @Nested
    @DisplayName("heap histogram rules")
    class Heap {

        @Test
        @DisplayName("HIS001 flags a dominant class, but not on a small heap")
        void topConsumer() {
            var big = withHisto(TextSource.of("heap.histo", TextFiles.splitLines(Fixtures.histo(
                    List.<Object[]>of(new Object[]{"[B", 2_000_000L, 200_000_000L},
                            new Object[]{"java.lang.String", 10_000L, 480_000L}),
                    200_480_000L, 2_010_000L), java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(1, run(rule("HIS001"), big).size());
            var small = withHisto(Fixtures.source("histo-jdk17.histo"));
            assertIds(run(rule("HIS001"), small), "a 6 MB histogram proves nothing about dominance");
        }

        @Test
        @DisplayName("HIS002 names an application class worth opening in MAT")
        void matWorthy() {
            var snap = withHisto(TextSource.of("heap.histo", TextFiles.splitLines(Fixtures.histo(
                    List.<Object[]>of(new Object[]{"[B", 100_000L, 8_000_000L},
                            new Object[]{"com.example.report.RowBuffer", 400_000L, 120_000_000L}),
                    128_000_000L, 500_000L), java.nio.charset.StandardCharsets.UTF_8)));
            var findings = run(rule("HIS002"), snap);
            assertEquals(1, findings.size());
            assertEquals("com.example.report.RowBuffer", findings.get(0).metrics().get("class"));
            assertIds(run(rule("HIS002"), withHisto(Fixtures.source("histo-jdk17.histo"))));
        }

        @Test
        @DisplayName("HIS003 catches millions of map nodes even when bytes look reasonable")
        void containerCount() {
            var snap = withHisto(TextSource.of("heap.histo", TextFiles.splitLines(Fixtures.histo(
                    List.<Object[]>of(new Object[]{"java.util.HashMap$Node", 3_000_000L, 96_000_000L},
                            new Object[]{"java.util.HashMap", 3_000_000L, 72_000_000L}),
                    200_000_000L, 6_000_000L), java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(1, run(rule("HIS003"), snap).size());
            assertIds(run(rule("HIS003"), withHisto(Fixtures.source("histo-jdk17.histo"))));
        }
    }

    @Nested
    @DisplayName("exception rules")
    class Exc {

        @Test
        @DisplayName("EXC001 groups by root-cause stack and counts occurrences")
        void clusters() {
            var snap = withStacks(Fixtures.source("app-exceptions.log"));
            var findings = run(rule("EXC001"), snap);
            assertFalse(findings.isEmpty());
            assertTrue(findings.get(0).summary().contains("SocketTimeoutException"),
                    () -> "the most frequent cluster should come first: " + findings.get(0).summary());
            assertTrue(findings.get(0).summary().endsWith("."));
        }

        @Test
        @DisplayName("EXC001 says nothing when the log has fewer than the threshold of repeats")
        void clustersNegative() {
            assertIds(run(rule("EXC001"), withStacks(Fixtures.source("app-healthy.log"))));
        }

        @Test
        @DisplayName("EXC002 attributes the fault below the framework wrapper")
        void causalChain() {
            var snap = withStacks(Fixtures.source("app-exceptions.log"));
            var findings = run(rule("EXC002"), snap);
            assertFalse(findings.isEmpty());
            Finding f = findings.get(0);
            assertTrue(String.valueOf(f.metrics().get("frame")).startsWith("dev.jingyu"),
                    () -> "expected an application frame, got " + f.metrics().get("frame"));
            assertTrue(f.summary().contains("SocketTimeoutException"));
        }

        @Test
        @DisplayName("EXC003 finds the minute where a cluster exploded")
        void burst() {
            var snap = withStacks(Fixtures.source("app-exceptions.log"));
            var findings = run(rule("EXC003"), snap);
            assertFalse(findings.isEmpty(), "five SocketTimeoutExceptions inside two minutes is a burst");
            org.junit.jupiter.api.Assertions.assertNotNull(findings.get(0).metrics().get("windowStartMillis"));
        }

        @Test
        @DisplayName("EXC003 needs timestamps and will not invent a burst without them")
        void burstNegative() {
            var undated = withStacks(TextSource.of("app.log", TextFiles.splitLines(
                    repeated(12, "java.lang.IllegalStateException: no time here\n"
                            + "\tat com.example.X.run(X.java:3)\n\n"), java.nio.charset.StandardCharsets.UTF_8)));
            assertIds(run(rule("EXC003"), undated));
        }

        private static String repeated(int n, String block) {
            return block.repeat(n);
        }
    }

    @Test
    @DisplayName("every rule documents itself and declares an artifact")
    void catalogueIsComplete() {
        assertEquals(18, Rules.all().size());
        for (Rule r : Rules.all()) {
            assertTrue(r.doc().startsWith("# " + r.id()), r.id() + " doc must start with its own heading");
            assertTrue(r.doc().contains("False positives"), r.id() + " must state its own limits");
            assertTrue(r.doc().contains("Evidence"), r.id() + " must say what it quotes");
            assertFalse(r.title().isBlank());
            assertTrue(r.artifact() != dev.jingyu.jia.model.ArtifactKind.UNKNOWN,
                    r.id() + " must declare which artifact it reads");
        }
    }

    @Test
    @DisplayName("applies() keeps a rule from running on an artifact it was not given")
    void notApplicable() {
        Snapshot empty = Snapshot.builder().build();
        for (Rule r : Rules.all()) {
            assertFalse(r.applies(empty), r.id() + " should not apply to an empty snapshot");
            assertTrue(run(r, empty).isEmpty(), r.id() + " must produce nothing on an empty snapshot");
        }
    }
}
