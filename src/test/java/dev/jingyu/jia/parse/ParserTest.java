package dev.jingyu.jia.parse;

import dev.jingyu.jia.Fixtures;
import dev.jingyu.jia.model.ArtifactKind;
import dev.jingyu.jia.model.ClassStat;
import dev.jingyu.jia.model.ExceptionOccurrence;
import dev.jingyu.jia.model.GcEvent;
import dev.jingyu.jia.model.GcLog;
import dev.jingyu.jia.model.Histo;
import dev.jingyu.jia.model.JThread;
import dev.jingyu.jia.model.LockRef;
import dev.jingyu.jia.model.TextSource;
import dev.jingyu.jia.model.ThreadDump;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("parsing layer")
class ParserTest {

    @Nested
    @DisplayName("thread dumps")
    class Threads {

        @Test
        @DisplayName("reads a real JDK 17 deadlock capture, including the trailer")
        void realDeadlockDump() {
            var rep = ThreadDumpParser.parse(Fixtures.source("jdk17-deadlock.jstack"));
            List<ThreadDump> dumps = rep.value();
            assertEquals(1, dumps.size());
            ThreadDump d = dumps.get(0);
            assertEquals(9, d.size(), "every \"…\" stanza counts, app and VM threads alike");
            assertTrue(d.vmReportedDeadlock());
            assertEquals(1, d.vmDeadlockCount());
            assertFalse(d.deadlockLines().isEmpty(), "the trailer must be collected, not parsed as threads");
            assertEquals("2026-09-20 21:04:11", d.capturedAtRaw(),
                    "the capture date sits on the line before 'Full thread dump' and must survive it");
            assertNotNull(Epochs.toMillis(d.capturedAtRaw()));

            JThread a = thread(d, "transfer-worker-1");
            assertEquals(JThread.ThreadState.BLOCKED, a.state());
            assertEquals("0x000000071ab8f010", address(a, LockRef.Kind.WAITING_TO_LOCK));
            assertEquals("0x000000071ab8f020", address(a, LockRef.Kind.HELD));
            assertEquals("com.example.settlement.TransferService", a.stack().get(0).declaringClass());
            assertEquals(88, a.stack().get(0).sourceLine());
        }

        @Test
        @DisplayName("strips JDK 9+ module prefixes from frame class names")
        void modulePrefixes() {
            ThreadDump d = ThreadDumpParser.parse(Fixtures.source("jdk17-healthy.jstack")).value().get(0);
            JThread main = thread(d, "main");
            assertEquals("jdk.internal.misc.Unsafe", main.stack().get(0).declaringClass(),
                    () -> "raw frame was: " + main.stack().get(0));
            assertEquals("java.lang.Thread", main.stack().get(main.stack().size() - 1).declaringClass());
            assertTrue(main.stack().get(0).nativeMethod());
        }

        @Test
        @DisplayName("keeps line numbers so findings can quote the file")
        void lineNumbers() {
            ThreadDump d = ThreadDumpParser.parse(Fixtures.source("jdk17-healthy.jstack")).value().get(0);
            JThread t = thread(d, "http-nio-8080-exec-7");
            assertTrue(t.startLine() > 0);
            assertTrue(t.endLine() >= t.startLine());
            assertEquals(t.startLine(), d.source().lines().indexOf(d.source().line(t.startLine())) + 1);
            assertTrue(d.source().line(t.startLine()).startsWith("\"http-nio-8080-exec-7\""));
        }

        @Test
        @DisplayName("a healthy capture parses without losing threads to the noise sections")
        void healthyDumpHasNoBlockers() {
            ThreadDump d = ThreadDumpParser.parse(Fixtures.source("jdk17-healthy.jstack")).value().get(0);
            assertTrue(d.byState().getOrDefault(JThread.ThreadState.BLOCKED, 0L) == 0);
            assertEquals(4, (int) d.threads().stream().filter(t -> t.index() == null).count(),
                    "GC and VM threads carry no # index");
        }

        @Test
        @DisplayName("does not attribute the deadlock trailer's locks to the last real thread")
        void trailerCannotStealMonitors() {
            var src = Fixtures.dump()
                    .idleWorker("main-idle", 10, 10)
                    .blocked("a", "com.x.A.run(A.java:1)", "0xaaa", "0xbbb")
                    .blocked("b", "com.x.B.run(B.java:1)", "0xbbb", "0xaaa")
                    .jstackDeadlockTrailer("a", "b", "0xbbb", "0xaaa")
                    .build("t.dump");
            ThreadDump d = ThreadDumpParser.parse(src).value().get(0);
            assertEquals(3, d.size(), "the trailer must not create or extend stanzas");
            for (JThread t : d.threads()) {
                if (!t.name().equals("a") && !t.name().equals("b")) {
                    assertTrue(t.locks().stream().noneMatch(l -> l.address().equals("0xaaa")
                                    || l.address().equals("0xbbb")),
                            t.name() + " must not inherit the trailer's monitors: " + t.locks());
                }
            }
        }

        @Test
        @DisplayName("two captures in one file become two dumps")
        void concatenatedDumps() {
            TextSource one = Fixtures.dump().idleWorker("w", 1, 1).build("x");
            TextSource two = Fixtures.dump().idleWorker("w", 2, 2).build("x");
            var joined = TextSource.of("both.dump",
                    java.util.stream.Stream.concat(one.lines().stream(), two.lines().stream()).toList());
            assertEquals(2, ThreadDumpParser.parse(joined).value().size());
        }

        @Test
        @DisplayName("garbage input yields no dump and an honest note instead of a crash")
        void malformed() {
            var rep = ThreadDumpParser.parse(TextSource.of("junk",
                    List.of("hello", "", "   at nonsense", "\t- locked not-an-address")));
            assertTrue(rep.value().isEmpty());
            assertFalse(rep.notes().isEmpty());
        }
    }

    @Nested
    @DisplayName("GC logs")
    class Gc {

        @Test
        @DisplayName("unified -Xlog:gc* becomes a normalised timeline")
        void unified() {
            String text = Fixtures.gcUnified(
                    List.of(new double[]{1.0, 60, 12, 5}, new double[]{4.0, 70, 14, 6}),
                    List.of(new double[]{10.0, 250, 120, 900}), null);
            GcLog log = GcLogParser.parse(TextSource.of("gc.log",
                    TextFiles.splitLines(text, java.nio.charset.StandardCharsets.UTF_8))).value();
            assertEquals(GcLog.Collector.G1, log.collector());
            assertTrue(log.unified());
            assertEquals(3, log.events().size());
            GcEvent full = log.events().get(2);
            assertEquals(GcEvent.Kind.FULL, full.kind());
            assertEquals(900.0, full.pauseMs(), 0.001);
            assertEquals(120L * 1048576, full.heapAfterBytes());
            assertEquals(256L * 1048576, full.capacityBytes());
            assertEquals("G1 Compaction Pause", full.cause());
            assertNotNull(log.bootEpochMillis(), "both decorators present, so the JVM start is derivable");
        }

        @Test
        @DisplayName("ZGC: statistics are not pauses, the cycle is the major collection, stalls are events")
        void zgcUnifiedVocabulary() {
            // A 125-line slice of a real ZGC capture (corpus/incident-zgc-leak), deliberately taken
            // from the middle of the file so it carries no "Using …" banner either.
            GcLog log = GcLogParser.parse(Fixtures.source("gc-jdk17-zgc.log")).value();
            assertEquals(GcLog.Collector.ZGC, log.collector(),
                    "the collector has to come from the pause vocabulary when the banner is rotated away");
            assertTrue(log.unified());
            assertTrue(log.events().stream().noneMatch(e -> e.kind() == GcEvent.Kind.FULL),
                    "ZGC never prints Pause Full");

            List<GcEvent> majors = log.majorCollections();
            assertEquals(3, majors.size());
            GcEvent cycle = majors.get(1);
            assertEquals("Allocation Stall", cycle.cause(), "a percentage is not a cause");
            assertEquals(1014L * 1048576, cycle.heapAfterBytes());
            assertEquals(1024L * 1048576, cycle.capacityBytes(), "Max Capacity: 1024M(100%) is the denominator");
            for (GcEvent e : majors) {
                assertTrue(e.pauseMs() < 1.0,
                        () -> "a ZGC cycle stops the world for microseconds, saw " + e.pauseMs() + " ms on line "
                                + e.line());
            }

            List<GcEvent> stalls = log.allocationStalls();
            assertEquals(2, stalls.size());
            assertTrue(stalls.get(0).cause().contains("http-nio-18081-exec-8"),
                    () -> "the thread ZGC stopped is the useful part of the line, was: " + stalls.get(0).cause());
            assertEquals(31.866, stalls.get(0).pauseMs(), 0.001);
            // A stall stops the thread that asked for memory, not the world. It belongs to GCA007;
            // letting it into the pause distribution would hand GCA002 a 32 ms "pause" and GCA006 a
            // throughput deficit this JVM never had.
            assertTrue(log.pauseMax() < 1.0,
                    () -> "a per-thread stall leaked into the pause statistics: max " + log.pauseMax() + " ms");
            assertEquals(61.93, log.stallSumMs(), 0.01);

            // The regression these two assertions exist for: [gc,stats] prints a rolling-averages
            // table inside every cycle, and a cell like "30.142 / 613.231 … ms" used to be read as
            // that collection's stop-the-world time — which turned a 0.02 % JVM into a reported
            // 63.4 %. Everything counted here is either a real phase or a real stall.
            assertTrue(log.pauseSumMs() < 100.0,
                    () -> "pause total inflated to " + log.pauseSumMs() + " ms — statistics rows are being counted");
        }

        @Test
        @DisplayName("JDK 8 traditional Parallel GC lines parse, including old gen and metaspace")
        void traditionalJdk8() {
            GcLog log = GcLogParser.parse(Fixtures.source("gc-jdk8-parallel.log")).value();
            assertFalse(log.unified());
            assertEquals(GcLog.Collector.PARALLEL, log.collector());
            List<GcEvent> full = log.majorCollections();
            assertEquals(5, full.size());
            GcEvent f = full.get(0);
            assertEquals(12345.678, f.pauseMs(), 0.01);
            assertEquals(20L * 1048576, f.oldAfterBytes());
            assertEquals(3L * 1048576, f.metaspaceAfterBytes());
            assertEquals("Ergonomics", f.cause());
            assertEquals(502784L * 1024, f.capacityBytes(), "the outermost (heap) transition wins");
            assertEquals(20L * 1048576 + 1024, f.heapAfterBytes(), 1024.0);
        }

        @Test
        @DisplayName("humongous and to-space-exhausted are attached to their own event")
        void promotionSignals() {
            GcLog log = GcLogParser.parse(Fixtures.source("gc-jdk17-storm.log")).value();
            assertTrue(log.events().stream().anyMatch(GcEvent::humongous));
            assertTrue(log.events().stream().anyMatch(GcEvent::toSpaceExhausted));
        }

        @Test
        @DisplayName("percentiles and throughput are computed over what was parsed")
        void statistics() {
            GcLog log = GcLogParser.parse(Fixtures.source("gc-jdk17-storm.log")).value();
            assertTrue(log.pauseMax() >= log.pausePercentile(99));
            assertTrue(log.throughput() < 0.9, "the storm fixture must actually look like a storm");
            assertTrue(log.durationSec() > 5);
        }

        @Test
        @DisplayName("an unrecognised log reports itself rather than pretending to be empty-silence")
        void malformed() {
            var rep = GcLogParser.parse(TextSource.of("gc.log", List.of("0.010s info gc nothing", "x")));
            assertTrue(rep.value().events().isEmpty());
            assertFalse(rep.notes().isEmpty());
        }
    }

    @Nested
    @DisplayName("heap histograms")
    class Heap {

        @Test
        @DisplayName("parses JDK 9+ rows with the module suffix and the Total line")
        void jdk17() {
            Histo h = HistoParser.parse(Fixtures.source("histo-jdk17.histo")).value();
            assertEquals(6, h.classes().size());
            ClassStat leader = h.classes().get(0);
            assertEquals("[B", leader.className(), "the module suffix must be stripped");
            assertEquals(3107072L, leader.bytes());
            assertEquals(49996L, leader.instances());
            assertEquals(6096632L, h.totalBytes());
            assertEquals(140678L, h.totalInstances());
            assertTrue(h.classes().get(2).className().endsWith("$Node"));
            assertEquals("java.lang.String", h.classes().get(1).baseClass());
        }

        @Test
        @DisplayName("parses JDK 8 rows without a module column")
        void jdk8() {
            Histo h = HistoParser.parse(Fixtures.source("histo-jdk8.histo")).value();
            assertFalse(h.classes().isEmpty());
            assertTrue(h.classes().stream().allMatch(c -> !c.className().contains("(java.base")));
            ClassStat leader = h.topByBytes(1).get(0);
            assertTrue(leader.bytes() > 0);
        }

        @Test
        @DisplayName("totals fall back to a sum when the Total row is missing")
        void missingTotal() {
            var src = TextSource.of("h.histo", List.of(
                    " num     #instances         #bytes  class name",
                    "----------------------------------------------",
                    "   1:          1000         240000  [B",
                    "   2:           900          72000  java.lang.String"));
            var rep = HistoParser.parse(src);
            assertEquals(312000L, rep.value().totalBytes());
            assertTrue(rep.notes().stream().anyMatch(n -> n.contains("Total")));
        }
    }

    @Nested
    @DisplayName("exception stacks")
    class Stacks {

        @Test
        @DisplayName("keeps the full throwable name, the chain and logback's jar suffix")
        void logbackFormat() {
            var rep = StackParser.parse(Fixtures.source("app-exceptions.log"));
            List<ExceptionOccurrence> found = rep.value();
            assertFalse(found.isEmpty());
            ExceptionOccurrence first = found.get(0);
            assertTrue(first.outermost().className().endsWith("Exception")
                            || first.outermost().className().endsWith("Error"),
                    () -> "truncated class name: " + first.outermost().className());
            assertTrue(first.hasCauseChain());
            assertTrue(first.rootBusinessFrame().isPresent());
            assertNotNull(first.epochMillis(), "logback stamps are parseable");
            assertNotNull(first.timestampRaw());
            first.rootCause().frames().forEach(f -> assertFalse(f.declaringClass().contains("~"),
                    "the ~[jar:version] suffix must not leak into the class name: " + f.declaringClass()));
        }

        @Test
        @DisplayName("a one-line warning that merely names an exception is not a stack")
        void noFalseStacks() {
            var rep = StackParser.parse(TextSource.of("app.log", List.of(
                    "2026-09-20 21:00:00.000  WARN 1 --- [main] c.e.Service : retrying after java.io.IOException: reset",
                    "2026-09-20 21:00:01.000  INFO 1 --- [main] c.e.Service : started")));
            assertTrue(rep.value().isEmpty(), "single-line mentions must not become findings");
        }

        @Test
        @DisplayName("OutOfMemoryError blocks are captured, since they are Errors not Exceptions")
        void errorsToo() {
            var rep = StackParser.parse(TextSource.of("app.log", List.of(
                    "2026-09-20 21:00:00.000 ERROR 1 --- [main] c.e.X : boom",
                    "java.lang.OutOfMemoryError: Java heap space",
                    "\tat com.example.Big.alloc(Big.java:10) ~[!/:1.0.0]",
                    "\tat java.lang.Thread.run(Thread.java:833) ~[na:na]")));
            assertEquals(1, rep.value().size());
            assertEquals("java.lang.OutOfMemoryError", rep.value().get(0).outermost().className());
        }
    }

    @Nested
    @DisplayName("file identification")
    class Sniff {

        @Test
        @DisplayName("content wins over a lying filename")
        void contentFirst() {
            var dump = Fixtures.dump().idleWorker("w", 1, 1).build("whatever");
            assertEquals(ArtifactKind.THREAD_DUMP, Sniffer.sniff("notes.txt", dump.lines()));
            var gc = TextSource.of("mystery.txt", TextFiles.splitLines(Fixtures.gcUnified(
                    List.of(new double[]{1, 60, 12, 5}), List.of(), null),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(ArtifactKind.GC_LOG, Sniffer.sniff("mystery.txt", gc.lines()));
            var histo = TextSource.of("data.log", TextFiles.splitLines(Fixtures.histo(
                    List.<Object[]>of(new Object[]{"[B", 100L, 1000L},
                            new Object[]{"java.lang.String", 90L, 2160L},
                            new Object[]{"com.example.Row", 12L, 960L}),
                    4120L, 202L),
                    java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(ArtifactKind.HEAP_HISTO, Sniffer.sniff("data.log", histo.lines()));
        }

        @Test
        @DisplayName("a JSON report or an unknown blob is reported as unknown")
        void unknown() {
            assertEquals(ArtifactKind.UNKNOWN, Sniffer.sniff("readme.md",
                    List.of("# heading", "prose about things", "with no signatures at all")));
        }
    }

    @Test
    @DisplayName("size and timestamp helpers")
    void helpers() {
        assertEquals(256L * 1048576, Sizes.parseBytes("256M"));
        assertEquals(128L * 1024, Sizes.parseBytes("128.0K"));
        assertEquals(0L, Sizes.parseBytes("0.0B"));
        assertNullOk(Sizes.parseBytes("nonsense"));
        assertNotNull(Epochs.toMillis("2026-09-20T21:00:00.100+0800"));
        assertNotNull(Epochs.toMillis("2026-09-20 21:00:00,123"));
        assertNotNull(Epochs.fromLine("2026-09-20 21:00:00.100  INFO 1 --- [main] x : hi"));
        assertNullOk(Epochs.fromLine("no stamp here"));
    }

    private static void assertNullOk(Long v) {
        assertTrue(v == null, "unparseable stamps must return null, never throw");
    }

    private static JThread thread(ThreadDump d, String name) {
        return d.threads().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no thread " + name + " in " + d.threads().stream()
                        .map(JThread::name).toList()));
    }

    private static String address(JThread t, LockRef.Kind kind) {
        return t.locksOfKind(kind).stream().findFirst().map(LockRef::address).orElse(null);
    }
}
