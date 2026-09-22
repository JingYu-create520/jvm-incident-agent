<!-- GENERATED FILE - do not edit by hand. Regenerate with scripts/render-rules.sh. -->

# Rule catalogue

19 rules ship in jvm-incident-agent 0.3.1.

Every finding they raise quotes `file:line` evidence from the artifact that was read,
and the text of each section is the same `doc()` the CLI serves from Java:

```bash
jia explain TDA001     # one rule, straight from the jar
jia rules              # the short form of the index below
```

## Index

| ID | Artifact | Detects |
| --- | --- | --- |
| [EXC001](#exc001) | application log | Repeated exception cluster |
| [EXC002](#exc002) | application log | Root cause attributed to application code |
| [EXC003](#exc003) | application log | Exception burst in time |
| [GCA001](#gca001) | GC log | Full GC storm |
| [GCA002](#gca002) | GC log | GC pause over SLA |
| [GCA003](#gca003) | GC log | Rising post-GC live set (memory leak fingerprint) |
| [GCA004](#gca004) | GC log | Premature promotion / allocation pressure |
| [GCA005](#gca005) | GC log | JVM configuration smell |
| [GCA006](#gca006) | GC log | GC throughput below target |
| [GCA007](#gca007) | GC log | Allocation stalled waiting for memory |
| [HIS001](#his001) | heap histogram | Heap dominated by one class |
| [HIS002](#his002) | heap histogram | Application class holds a large share of the heap |
| [HIS003](#his003) | heap histogram | Implausible number of collection instances |
| [TDA001](#tda001) | thread dump | Deadlock (cycle in the wait-for graph) |
| [TDA002](#tda002) | thread dump | Contended monitor (one lock, many waiters) |
| [TDA003](#tda003) | thread dump | Thread leak (growing or oversized thread family) |
| [TDA004](#tda004) | thread dump | Stack hotspot (many threads stuck in the same place) |
| [TDA005](#tda005) | thread dump | Thread pool starved (no idle worker left) |
| [TDA006](#tda006) | thread dump | Thread burning CPU |

## Rules

## EXC001

**Repeated exception cluster** · application log · `jia explain EXC001`

Reduce every throwable block in the log to a fingerprint — root-cause class plus the first five
frames, line numbers stripped — then count.

Grouping on the *root cause* rather than the wrapper is the whole design. A service that logs
`DataIntegrityViolationException` for thirty different constraint failures should produce thirty
clusters, and the four hundred identical `NullPointerException`s behind them should collapse into
one. Group by the outer class instead and you get the reverse: one meaningless row of five hundred.

Severity stays MEDIUM unless the root cause is an `Error`. The log cannot tell you whether an
exception was handled gracefully upstream, and pretending otherwise is how these tools get
ignored; what it can tell you is that the same stack came back five or more times
(`--exception-threshold`).

Evidence: the root-cause frames themselves, then one line per occurrence with its log timestamp
where the logger printed one.

Wrong when: one expected, handled failure that simply happens often. From the log alone that is
indistinguishable from a bug, which is why the finding names the class and the stack and stops
there — it never claims the exception went unhandled, and severity stays MEDIUM unless the root
cause is an `Error`.

---

## EXC002

**Root cause attributed to application code** · application log · `jia explain EXC002`

**What it looks for.** For every stack with a `Caused by:` chain, the last link is taken and
scanned top-down for the first frame that is neither a JDK nor a known framework package
(`java.*`, `jdk.*`, `sun.*`, Spring, Tomcat, Netty, Hibernate, …). Occurrences that bottom
out at the same exception class *and* the same business line are counted together.

**Why it is trustworthy.** Frameworks re-throw: a controller logs
`DataIntegrityViolationException`, five frames and three wrappers down is
`SQLException` at your DAO line. Attributing the fault to the wrapper sends people to the
wrong file. The exclusion list is explicit, and the finding quotes the whole chain
(`A ← B ← C`) so the attribution can be re-checked in the log.

**Evidence.** The start and end line of each occurrence, annotated with its chain.

**False positives.** If your own code lives under a package that looks like a framework
(say `org.apache.yourapp`), the attribution falls back to the JDK boundary and may name a
framework frame instead. Two dumps of the same log make that obvious.

---

## EXC003

**Exception burst in time** · application log · `jia explain EXC003`

Bucket one stack fingerprint's occurrences into one-minute windows by their parsed log timestamp,
and fire at ten or more inside a window. That minute becomes the start of the incident: whatever
changed then is the candidate cause.

Rate needs time, and time exists only if the lines carry stamps. With fewer than ten parseable
timestamps this rule returns nothing rather than guessing, and the window it reports is a real
bucket boundary, not a mean.

Evidence: the occurrences inside the burst, annotated with their own timestamps. Wrong when: a
restart loop repeating one stack — that shows a burst per restart, which the per-line timestamps
make visible.

---

## GCA001

**Full GC storm** · GC log · `jia explain GCA001`

**What it looks for.** Full GC events are placed on the uptime axis and a sliding
window (`--gc-window`, default 300 s) finds the densest burst. The burst rate is
compared with `--full-gc-per-min` (default 1/min).

**Why it is trustworthy.** Counting Full GCs over the whole log dilutes the burst that
actually hurt: twenty collections spread over a day of warm-up is tuning noise, twenty
inside five minutes is an outage. The window reports the burst, and the total is kept
as a separate metric.

**Evidence.** Each Full GC line in the densest window, annotated with uptime and cause.

**False positives.** Full GCs caused by `Metadata GC Threshold` inside the first `--gc-settle-sec`
(default 60 s of uptime) and ones an outside actor forced (`Heap Inspection Initiated GC` from
`jmap -histo:live`, a heap dump) are dropped before the rate is computed: they are real collections
and they say nothing about memory pressure. That filter is the reason a 78-line startup slice of a
healthy Parallel GC service produces no findings; without it the same bytes came back as a storm.
Nothing filters `GCLocker Initiated GC` -- that one is the JVM complaining, and GCA005 quotes it.

A log covering less than one window at the very end of the JVM's
life, or shutdown-time `System.gc()` calls, can look dense. The causes printed next to
each event let a reader check: `Allocation Failure`/`Ergonomics` is pressure,
`System.gc()` is somebody's code.

---

## GCA002

**GC pause over SLA** · GC log · `jia explain GCA002`

Collect every stop-the-world pause, rank them, compare the distribution against `--sla-ms`
(default 200 ms). p50, p95, p99 and the maximum all appear in the finding, because they answer
different questions: the maximum is what shows up in an incident review, p99 is what every tenth
request feels. Severity follows how far p99 sits over the line, not how bad the single worst pause
was.

Evidence is the five worst pause lines with their kind and cause.

Wrong when: the log is dominated by startup or shutdown, where long pauses are not the
application's fault — check the uptime printed next to each event before resizing anything.

---

## GCA003

**Rising post-GC live set (memory leak fingerprint)** · GC log · `jia explain GCA003`

This is the rule that separates "the app is allocating too much" from "the app is leaking", and it
is the reason the two look the same in every other tool: both produce a wall of Full GCs.

After a Full GC, garbage is gone. What remains is the live set. Sample that number at every major
collection and watch its floor over time.

Three shapes mean the same thing. A **climb** is the textbook case: the floor rises by at least 10%
(`--heap-leak-rise`) end to end without more than a quarter of the steps dipping. A **plateau** is
the same leak after it has filled the heap — the floor sits at 85%+ of capacity for three or more
collections and nothing is being reclaimed any more. A purely monotonic test misses the plateau
entirely, and the plateau is what a snapshot taken during an actual outage looks like: by the time
anyone captures anything, the leak has already saturated the heap. A **stalled floor** is the
plateau's quieter cousin: the level is well below the ceiling, but every major collection gives
back under 2% of what it was handed and the floor never once dips (Shenandoah on a 1 GB heap, at
63%: 649M->649M, 650M->650M). Requiring zero dips is what keeps an allocation storm out of this
branch — its floor oscillates with each batch, which is the entire difference between those two
captures.

Where the log exposes old-generation detail the rule uses it directly — G1 `Old regions:` scaled by
the region size printed at init, or a JDK 8 `[ParOldGen: …]` figure. Otherwise it uses post-GC heap
used, which for a Full GC is the same quantity.

Startup `Metadata GC Threshold` collections and inspection-forced Full GCs are excluded from the
series, for the reason GCA001 states: two of them during a Spring Boot start are not a rising floor.

Evidence: evenly spaced major-collection lines, each annotated with the live bytes it left behind,
ending with the last measurement. Severity follows how close to the ceiling the floor already is.

Wrong when: a cache legitimately filling to its configured maximum. That produces a rise then a
flat line; the dip tolerance rejects the flat part, but a capture taken entirely during fill-up will
look exactly like this. Check `samples` and the capacity share before believing it. The stalled
branch has a blunter limit: one snapshot cannot tell "leaked until the heap was 63 % full" from
"this process genuinely keeps 650 MB alive" — a Full GC that reclaims nothing is also what a steady
working set looks like. The recommendation is the same in both cases (find the holder), which is
why this rule's action is a heap-dump query rather than a flag to tune.

---

## GCA004

**Premature promotion / allocation pressure** · GC log · `jia explain GCA004`

**What it looks for.** Three independent log signals, any of which means garbage is
landing in the old generation:

- `to-space exhausted` / `Evacuation Failure` / `promotion failed` — the collector could
  not find room to copy survivors, so they were promoted in panic.
- humongous allocation *causes* — an object larger than half a G1 region skips young space
  entirely. Only the reason a collection happened counts: `Humongous regions: 228->228`
  in a `[gc,heap]` block and Shenandoah's `… 902M humongous …` free-space lines are
  occupancy accounting, and reading them as causes inflates the count by ~4x and once
  made this rule fire on a collector that has no young generation to promote into.
- young collections that reclaim under 5% of the heap repeatedly — the copy work happened
  but the objects did not die, which is promotion in all but name.

**Why it is trustworthy.** Each signal is the JVM's own words, matched on the line that
says it, so the evidence column quotes the collector rather than an interpretation.

**Evidence.** The offending collection lines with the matched signal annotated.

**False positives.** Fewer than three humongous events and fewer than eight substantial
low-yield young collections produce nothing, so a short or idle log stays quiet. An
idle JVM reclaiming a few megabytes is a quiet night, not promotion — the rule ignores
collections that started below 32 MB of occupied heap for that reason.

---

## GCA005

**JVM configuration smell** · GC log · `jia explain GCA005`

Two computed signals and a string table. The signals: collections whose recorded cause is
`Metadata GC Threshold` (metaspace is driving GC, not the heap) and any Full GC caused by
`System.gc()`. The table matches verbatim complaints the JVM writes when it wants a flag changed —
`concurrent mode failure`, `Could not reserve enough space for object heap`,
`Try -XX:+UseCompressedOops`, `GCLocker Initiated GC` — and quotes the line that said it, so the
recommendation can be checked rather than trusted.

Metaspace needs three or more triggers with at least one after the JVM has been up for a minute.
Wrong when: a Spring Boot startup, which produces one or two `Metadata GC Threshold` collections on
its own; reporting those is the fastest way to teach a team to ignore this tool.

---

## GCA006

**GC throughput below target** · GC log · `jia explain GCA006`

Sum of stop-the-world pauses divided by the wall-clock span of the log. Fires below 97%
(`--throughput`) and needs at least ten collections over ten seconds, so a short excerpt stays
quiet. Only the time when no application thread ran counts: a record's summary line or the sum
of its `Pause …` phases, never its `Concurrent …` durations, never a `[gc,stats]` cell, and
never an allocation stall — that last one stops a single thread and is GCA007's to report.

Throughput is arithmetic over the same pause numbers GCA002 reports; both the sum and the span are
printed in the finding. Evidence is the biggest contributing pauses with their share of the total.

Wrong when: the log covers a mostly idle period with two long pauses at the start — a small
denominator inflates the percentage. It is also a floor, never a ceiling: `-Xlog:gc*` cannot see
safepoint work that is not a GC pause, so the real figure can be worse than this, never better.

---

## GCA007

**Allocation stalled waiting for memory** · GC log · `jia explain GCA007`

ZGC has no Full GC to give you. When it cannot hand out memory fast enough it stops the
one thread that asked for it and prints `Allocation Stall (http-nio-8080-exec-8)
31.866ms` against that thread's name. Counting those lines is the only way this tool
can see a ZGC heap in trouble, because every other GC rule here is reading a pause
vocabulary ZGC does not use.

Fires at three or more stalls, or at a single stall longer than the pause SLA. The
thread names come out of the log, so "who is being starved" is part of the finding —
if it is your request threads and not a batch job, you are deciding about latency, not
about throughput.

Evidence: the stall lines themselves, longest first, each quoting the thread the JVM
named and the milliseconds it waited. One quotation per occurrence, never a count on
its own.

Wrong when: a stall is a moment, not a trend. One stall in a log covering an hour means
a hiccup; ten in a minute is the shape of an undersized heap. And ZGC is not the only
collector that stalls — under G1 the same pressure surfaces as to-space exhausted and
shows up as GCA004, not here.

---

## HIS001

**Heap dominated by one class** · heap histogram · `jia explain HIS001`

**What it looks for.** Rows of `jmap -histo` ranked by shallow bytes. A single class at
35% or more of counted bytes fires; separately, when `byte[] + char[] + String` together
exceed 60% the rule says the quiet part out loud — a histogram cannot tell you who holds
those, only that something does.

**Why it is trustworthy.** Shallow bytes are what the tool printed; nothing is inferred.
The `:live` marker is read from the capture so the report can tell you whether you are
looking at the live set or at garbage that a GC will remove.

**Evidence.** The leading rows with bytes, instance counts and their share.

**False positives.** An app whose job genuinely is buffering (file transfer, image
pipeline) has a boring, correct 70% of `byte[]`. Severity stays MEDIUM unless one class
takes over, and the recommendation is "diff two histograms", which distinguishes steady
state from growth.

---

## HIS002

**Application class holds a large share of the heap** · heap histogram · `jia explain HIS002`

The same histogram as HIS001, restricted to classes that are not `java.*`, `jdk.*`, `sun.*` or
arrays of them. If one of your own classes holds at least 10% of counted bytes and 8 MB absolute (`--mat-share`, `--mat-min-mb`),
it is named, along with the sibling classes that are large too.

The distinction matters because a `byte[]` at the top tells you a buffer grew, while a domain class
at the top tells you *which state* grew — and only the second one is actionable without a dump. The
recommendation is therefore a concrete MAT query: Histogram → the class → Merge Shortest Paths to
GC Roots, excluding weak and soft references.

Evidence: every application class above 2 MB, ranked, with instance counts. Wrong when: a
long-lived cache of your own objects, which is legitimately large — read the instance count
("10 MB across 4 entries" and "10 MB across 240,000 entries" are different bugs).

---

## HIS003

**Implausible number of collection instances** · heap histogram · `jia explain HIS003`

Ten thousand maps is a program. Two million is a bug, and the histogram can see the difference even
though it cannot see a single reference.

Instance counts for the standard containers — and their internal `$Node` / `$Entry` types, where the
entries of a `HashMap` or `ConcurrentHashMap` actually live — fire at 50,000 or more, or at 5% of
all objects in the table. Bytes can be legitimately large in a buffering application; half a million
separate map nodes almost never is.

Evidence: the offending row plus up to three corroborating container rows, so the finding rests on a
pattern across lines rather than one big number.

Wrong when: an in-memory data grid or a deep ORM entity graph, which really does hold hundreds of
thousands of nodes. Severity keys off bytes as well as count for that reason, and the
recommendation is to group by owning field, not a claim of leakage.

---

## TDA001

**Deadlock (cycle in the wait-for graph)** · thread dump · `jia explain TDA001`

Every `waiting to lock <0x…>` / `parking to wait for <0x…>` line is paired with
whichever thread prints `locked <0x…>` (or lists that address under *Locked ownable
synchronizers*) for the same monitor. That gives a directed graph over the threads in
one dump, edge from waiter to owner, and Tarjan's strongly-connected-components
algorithm returns every component with two or more members. Inside such a component
each thread waits on a monitor another member holds, so none of them can ever run
again: that is the definition of a deadlock, not a heuristic about it.

Two things make this more than a re-implementation of what jstack already prints.

The graph covers `java.util.concurrent` locks as well as monitors. A cycle of threads
parked on each other's `ReentrantLock` is completely invisible to jstack's own
detector — it prints no trailer at all — and shows up here. When the trailer does
fire and the graph agrees, confidence is 0.99; when only the graph fires, 0.92; when
the trailer fires but the lock lines will not reconstruct into a cycle, that is
reported as a parser limitation rather than dropped.

Tarjan is written iteratively. Recursion depth tracks chain length and a tired
production JVM can hold tens of thousands of threads; a stack overflow in the tool
you reach for at 3 a.m. is not an acceptable failure mode.

Evidence is the full stanza of each thread in the cycle plus the exact line where its
partner holds the monitor it is queued on.

Can be wrong when: effectively never, which is why this is the only thread rule with
no threshold. It would need two unrelated objects to share a monitor address.

---

## TDA002

**Contended monitor (one lock, many waiters)** · thread dump · `jia explain TDA002`

Three or more threads queued on the same monitor address (`--lock-waiters` to move the line) is a
queue, and a queue is a bottleneck. The rule ranks monitors by waiter count and names the holder —
resolved from the matching `- locked <0x…>` line — because the useful answer is not "there is
contention" but "here is the code everyone is standing behind".

Quotes the holder's stanza plus one line per waiter. The rule does not fire on a single thread
waiting on a single lock, and never counts the holder as a waiter on its own monitor.

---

## TDA003

**Thread leak (growing or oversized thread family)** · thread dump · `jia explain TDA003`

Thread names carry their origin: `pool-3-thread-17` came from one creation site, `http-nio-8080-exec-9`
from Tomcat's connector. Strip the numeric parts and group by what is left, and a family is a
group of threads somebody's code made.

Two independent tests then run on those families.

**Oversized.** A family larger than `--thread-leak-threshold` (default 40). Families the platform
bounds itself — `http-nio-*-exec`, `pool-N-thread-M`, `grpc-*`, and friends listed in
`SERVER_POOL` — need 200 or more before they fire, because a busy servlet container sitting at 150
worker threads is a normal Tuesday and reporting it would be the single most reliable way to make
people stop trusting this tool.

**Only growing.** Given two or more dumps, a family whose count never decreases and grows by at
least 25%. This is the test that catches a live leak rather than a large pool: bounded pools are
big and flat, leaked pools are small and monotonically increasing.

Evidence is one thread stanza per dump for the family, with the running count annotated, so the
growth is visible in the report itself rather than asserted by it.

Quiet on: a bespoke executor with a legitimately huge fixed pool under 200. Raise
`--thread-leak-threshold`, or capture two dumps so the growth test can do its job.

---

## TDA004

**Stack hotspot (many threads stuck in the same place)** · thread dump · `jia explain TDA004`

Group BLOCKED / WAITING / TIMED_WAITING threads by their state plus the top five frames, and
report any group of five or more. That is where the traffic jams. Line numbers are excluded from
the fingerprint, because otherwise one rebuild splits a single bug into two clusters.

The hard part is not the grouping. It is knowing what does not count.

Two hundred Tomcat workers parked in `ThreadPoolExecutor.getTask` is a healthy server at night. So
is a pool waiting on `SynchronousQueue.poll`, `LinkedBlockingQueue.take`, `LockSupport.park` under
`getTask`, or its own `Object.wait()`. A naive fingerprint count calls all of that a hotspot, which
is exactly why most home-grown thread-dump scripts get dismissed after one use. The exclusion list
lives in `ThreadNoise` and is written out frame by frame rather than inferred from "looks idle".
Threads sleeping in `Thread.sleep` are excluded too — that is a thread-count question for TDA003,
not a contention question.

A second pass catches the opposite disguise: threads reporting RUNNABLE while sitting in
`socketRead0`, `EPoll.wait` or `kevent0`. They are waiting on a peer, the JVM will not call them
blocked, and a dashboard counting blocked threads will look perfectly calm while every worker is
gone. That variant is tagged `socket-io` in the metrics so the report can rank it differently.

Evidence: one stanza per member of the cluster, with its state.

Wrong when: a custom executor whose take method is not in the idle list. Add the frame, or raise
`--stack-cluster`.

---

## TDA005

**Thread pool starved (no idle worker left)** · thread dump · `jia explain TDA005`

A pool where 4 of 10 threads wait in `getTask` has spare capacity. A pool where 10 of 10 are inside
`JdbcTemplate.query` is about to start rejecting work. The rule groups threads into pools by name
family, drops the idle ones using the same `ThreadNoise` list TDA004 relies on, and fires when at
least 80% of a pool is occupied *and* those busy workers share one business frame.

The same-frame requirement is what keeps ordinary concurrency from looking like starvation.
Families over 100 members are skipped on purpose: that is a leak, and TDA003 reports it with growth
evidence. Calling a 140-thread leak "starvation" would send the reader to pool sizing.

Evidence: each stuck worker's stanza with its state. Wrong when: a batch job that saturates its
pool deliberately — from one dump it is indistinguishable from starvation.

---

## TDA006

**Thread burning CPU** · thread dump · `jia explain TDA006`

`jstack` headers carry `cpu=` and `elapsed=`. With one dump the only honest number is a lifetime
average, `cpu / elapsed`, and the finding labels it that way and caps its confidence — a thread that
spun hard during startup and has been idle since still shows a high average.

With two dumps there is something much better: the delta of `cpu` over the delta of `elapsed` is
utilisation across the interval between captures, which is what "hot right now" actually means. The
metric `basis` says which of the two you are looking at, and the confidence follows (0.85 for a
delta, 0.6 for an average).

Threads are matched between dumps by `nid`, falling back to name. JIT compiler and GC threads are
excluded — they burn CPU as their job.

Quotes the header line with the raw `cpu=`/`elapsed=` values it came from, plus the top frame.
Quiet on JDK 8 dumps, which have no `cpu=` column at all; this rule does not guess.

---

_19 rules, rendered from `jvm-incident-agent 0.3.1 rules --format json` by scripts/render-rules.sh._
