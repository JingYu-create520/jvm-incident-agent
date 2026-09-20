<!-- GENERATED FILE - do not edit by hand. Regenerate with scripts/render-rules.sh. -->

# Rule catalogue

18 rules ship in jvm-incident-agent 0.1.0.

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

**What it looks for.** Every throwable block in the application log is reduced to a
fingerprint: root-cause class plus the first five frames, with line numbers removed so a
rebuild does not split one bug into two clusters. Anything appearing five or more times
(`--exception-threshold`) is reported, most frequent first.

**Why it is trustworthy.** The grouping key is the *root cause* stack, not the wrapper. A
service that logs `DataIntegrityViolationException` for 30 different constraint failures
produces 30 clusters, while the 400 identical `NullPointerException`s that caused them all
collapse into one — which is exactly what you want to see first.

**Evidence.** The root-cause frames themselves, then one line per occurrence with its log
timestamp where available.

**False positives.** A single expected, handled failure that happens often looks identical to
a bug from the log alone. The finding says which class and stack it is, never that it is
unhandled — that judgement stays with the reader, which is why severity is MEDIUM unless the
root cause is an `Error`.

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

**What it looks for.** Occurrences of one stack fingerprint are bucketed into one-minute
windows by their parsed log timestamp. Ten or more inside a single window fires; the finding
reports when the window starts.

**Why it is trustworthy.** Rate needs time, and time only exists if the log lines carry
stamps — this rule returns nothing at all when fewer than ten occurrences have parseable
timestamps, instead of guessing. The reported minute is a real bucket boundary, not a mean.

**Evidence.** The occurrences inside the burst window, annotated with their own timestamps.

**False positives.** A log that repeats one stack during a restart loop shows a burst per
restart. The count next to each line makes the pattern visible.

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

**False positives.** A log covering less than one window at the very end of the JVM's
life, or shutdown-time `System.gc()` calls, can look dense. The causes printed next to
each event let a reader check: `Allocation Failure`/`Ergonomics` is pressure,
`System.gc()` is somebody's code.

---

## GCA002

**GC pause over SLA** · GC log · `jia explain GCA002`

**What it looks for.** Every stop-the-world pause is collected and ranked. The
distribution (p50 / p95 / p99 / max) is compared with `--sla-ms` (default 200 ms), and
severity follows how far p99 — not the maximum — sits over the line.

**Why it is trustworthy.** A single 4 s pause in a six-hour log is a fluke; a p99 of
400 ms is what every tenth request feels. Reporting both numbers is the point.

**Evidence.** The five worst pause lines with their kind and cause.

**False positives.** A log that includes startup and shutdown, or that was captured
while the machine was swapping, shows long pauses that are not the app's fault. The
uptime next to each event lets you see whether they cluster at boot.

---

## GCA003

**Rising post-GC live set (memory leak fingerprint)** · GC log · `jia explain GCA003`

**What it looks for.** After every Full GC (and G1 mixed collection) the heap holds only
live objects. Those post-collection values are sampled in time order: if they climb by at
least 10% end to end (`--heap-leak-rise`) without more than a quarter of the steps
dipping, the live set is growing and something is holding references.

When the log exposes old-generation detail — G1 `Old regions:` scaled by the region size,
or a JDK 8 `[ParOldGen: …]` figure — the rule measures the old generation directly;
otherwise it uses post-GC heap used, which is the same quantity for a Full GC.

**Why it is trustworthy.** This is the definition of a leak from GC data alone, not a
heuristic about heap size. Capacity is read from the log so the finding can say how close
to the wall you already are, which is what decides severity.

**Evidence.** Evenly spaced major-collection lines, each annotated with the live bytes it
left behind, ending with the last measurement.

**False positives.** A cache that legitimately fills to its configured maximum produces a
rising then flat series; the dip tolerance rejects the flat part, but a snapshot taken
entirely during fill-up will look like this. Check `samples` and the capacity share.

---

## GCA004

**Premature promotion / allocation pressure** · GC log · `jia explain GCA004`

**What it looks for.** Three independent log signals, any of which means garbage is
landing in the old generation:

- `to-space exhausted` / `Evacuation Failure` / `promotion failed` — the collector could
  not find room to copy survivors, so they were promoted in panic.
- humongous allocation causes — an object larger than half a G1 region skips young space
  entirely.
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

**What it looks for.** Two computed signals — collections whose recorded cause is
`Metadata GC Threshold` (metaspace, not heap, is driving GC) and any Full GC caused by
`System.gc()` — plus a table of verbatim strings the JVM prints when it wants you to
change a flag: `concurrent mode failure`, `Could not reserve enough space for object
heap`, `Try -XX:+UseCompressedOops`, `GCLocker Initiated GC`, `Heap Dump Initiated GC`.

**Why it is trustworthy.** The string table quotes the JVM's own complaint and prints the
line it came from, so nobody has to take the recommendation on faith. Cause-based signals
need two or more occurrences before they fire.

**Evidence.** The matching log line, or the collection lines carrying the cause.

**False positives.** `Heap Dump Initiated GC` is reported at INFO, not as a fault — it is
a normal consequence of taking a dump, and the report says so.

---

## GCA006

**GC throughput below target** · GC log · `jia explain GCA006`

**What it looks for.** Sum of all stop-the-world pauses divided by the wall-clock span of
the log. Fires below 97% (tune with `--throughput`), and needs at least ten collections
over ten seconds so a short excerpt cannot trip it.

**Why it is trustworthy.** It is arithmetic over the same pause numbers GCA002 reports,
and both the sum and the span are printed in the finding. The known limit is honest: a log
with only the pauses visible to `-Xlog:gc*` cannot count safepoint work that is not a GC
pause, so the real figure can be worse than this, never better.

**Evidence.** The biggest contributing pauses with their share of the total.

**False positives.** A log covering a mostly idle period with two long pauses at the start
(class loading, JIT) divides by a small denominator. Minimum event and span counts handle
the common case; for a five-line excerpt the tool stays quiet anyway.

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

**What it looks for.** The same table as HIS001, restricted to classes that are not
`java.*` / `jdk.*` / `sun.*` / arrays of those. If one of your own classes holds at least
10% of counted bytes and 8 MB absolute, the report names it and the sibling classes that
are large too.

**Why it is trustworthy.** A `byte[]` at the top tells you a buffer grew; a domain class at
the top tells you *which* state grew. Both are needed, and only the second one is
actionable without a dump — so the recommendation is the exact MAT query to run.

**Evidence.** Every application class above 2 MB, ranked, with instance counts.

**False positives.** Long-lived caches of your own objects are legitimately large. The
instance count in the summary is what lets a reader judge "10 MB across 4 entries" versus
"10 MB across 240 000 entries".

---

## HIS003

**Implausible number of collection instances** · heap histogram · `jia explain HIS003`

**What it looks for.** Instance counts for the standard collection classes — and their
internal `$Node` / `$Entry` types, which is where the entries of a `HashMap` or
`ConcurrentHashMap` actually live. Anything at 50 000+ instances, or 5% of all objects in
the histogram, fires.

**Why it is trustworthy.** Bytes can be legitimately large in a buffering application; half
a million separate map nodes almost never is. The finding quotes corroborating rows from
the same table, so it is a pattern across several lines rather than one big number.

**Evidence.** The offending row plus up to three related container rows.

**False positives.** Big in-memory data grids and ORMs with deep entity graphs really do
hold hundreds of thousands of nodes. That is why severity keys off bytes as well as count,
and why the recommendation is to group by owning field rather than a claim of leakage.

---

## TDA001

**Deadlock (cycle in the wait-for graph)** · thread dump · `jia explain TDA001`

**What it looks for.** Every `waiting to lock <0x…>` / `parking to wait for <0x…>`
line is paired with whichever thread prints `locked <0x…>` (or lists that address
under *Locked ownable synchronizers*) for the same monitor address. That produces a
directed wait-for graph over the threads in one dump. Tarjan's strongly-connected
components algorithm finds every component with two or more members — inside such a
component each thread waits on a monitor held by another member, so no member can
ever proceed.

**Why it is trustworthy.** This is the same graph the JVM itself builds when it
prints `Found one Java-level deadlock`. TDA001 runs the algorithm independently and
then cross-checks: if the JVM trailer agrees, confidence is 0.99; if the JVM said
nothing but a cycle exists, the tool still reports it (a `ReentrantLock` cycle is
invisible to jstack's own detector); if the trailer fires but no cycle can be
reconstructed, that is reported as a parser limitation rather than hidden.

**Evidence.** The full stanza of each thread in the cycle, plus the exact line where
the owner holds the monitor the next thread is waiting for.

**False positives.** Essentially none by construction — a cycle in a wait-for graph
is a definition, not a heuristic. A healthy dump produces zero edges between owners
and waiters and cannot fire.

---

## TDA002

**Contended monitor (one lock, many waiters)** · thread dump · `jia explain TDA002`

**What it looks for.** Every `- waiting to lock <0x…>` line names a monitor
address. Addresses with three or more distinct waiters (tune with
`--lock-waiters`) are counted and ranked by in-degree — the most-contended
lock in a dump is almost always the throughput bottleneck, and it is the
lock, not the threads, that needs fixing.

**Why it is trustworthy.** The holder is resolved from the matching
`- locked <0x…>` line, so the finding names the exact code everyone is queued
behind, not just "there is contention". The holder's own stack is quoted as
evidence; if it is inside I/O, an RPC or a log call, that is your fix.

**Evidence.** The holder's thread stanza, plus one line per waiter showing the
monitor it is queued on.

**False positives.** A single waiter on a lock is normal and produces nothing;
only a queue of three or more fires. The thread that owns the monitor is never
counted as a waiter on it.

---

## TDA003

**Thread leak (growing or oversized thread family)** · thread dump · `jia explain TDA003`

**What it looks for.** Thread names are grouped into families by stripping their
numeric suffixes (`pool-3-thread-17` → `pool-3-thread`). Two independent tests run:

1. **Oversized** — a family with more members than `--thread-leak-threshold`
   (default 40). Families the platform bounds itself (`http-nio-*-exec`,
   `pool-N-thread-M`, `grpc-*`) need 200+ before they fire, because a busy servlet
   container legitimately sits at 150.
2. **Only growing** — with two or more dumps, a family whose count never decreases
   and grows by at least 25%.

**Why it is trustworthy.** Test 2 is the one that catches a live leak: bounded pools
are large but flat, leaked pools are small then monotonically increasing. JVM
housekeeping threads are excluded outright.

**Evidence.** One quoted thread stanza per dump for the family, with the running count
in the annotation, so the growth is visible in the report itself.

**False positives.** A large-but-stable server pool. Handled by the separate, higher
bar for known pool name patterns; if your framework uses a custom prefix that is
legitimately huge, raise `--thread-leak-threshold`.

---

## TDA004

**Stack hotspot (many threads stuck in the same place)** · thread dump · `jia explain TDA004`

**What it looks for.** BLOCKED / WAITING / TIMED_WAITING threads are grouped by a
fingerprint of their state plus the top five frames (line numbers excluded, so a
rebuild does not split a cluster). Any group of five or more is a hotspot: that is
where the traffic jams.

A second pass catches the opposite disguise — threads that report RUNNABLE while
sitting in `socketRead0`, `EPoll.wait` or `epollWait`. They are waiting on a peer,
and a JVM without a timeout will happily keep them there forever.

**Why it is trustworthy.** Idle worker threads are excluded. Two hundred Tomcat
workers parked in `ThreadPoolExecutor.getTask` is a healthy server at night, and a
naive fingerprint count would call that a hotspot every single time. The exclusion
list is explicit in `ThreadNoise`, not inferred from "looks fine".

**Evidence.** One thread stanza per member of the cluster, with its state.

**False positives.** A deliberately large pool waiting on a shared queue that the
exclusion list does not recognise (a bespoke executor with a custom take method).
Raise `--stack-cluster` or add the frame to the idle list.

---

## TDA005

**Thread pool starved (no idle worker left)** · thread dump · `jia explain TDA005`

**What it looks for.** Threads are grouped into pools by name family. A pool fires
when at least 80% of its workers are doing something (i.e. not parked in the pool's
own work queue) **and** those busy workers share one business frame.

**Why it is trustworthy.** The idle-worker exclusion is the whole trick: a pool where
4 of 10 threads wait in `getTask` is normal, and a pool where 10 of 10 are inside
`JdbcTemplate.query` is about to reject traffic. The same-frame requirement stops
ordinary concurrency from looking like starvation.

**Evidence.** Each stuck worker's stanza, with its state.

**False positives.** A batch job that intentionally saturates its pool at full tilt
looks identical to starvation from one dump. Take two dumps seconds apart, or raise
`--pool-starve-ratio`.

---

## TDA006

**Thread burning CPU** · thread dump · `jia explain TDA006`

**What it looks for.** `cpu=` and `elapsed=` in each thread header. With one dump the
only honest number is a lifetime average (`cpu / elapsed`), so the finding labels it as
such and confidence is capped. With two dumps the delta of both columns gives
utilisation over the interval between captures, which is what "hot right now" means.

A thread fires at 0.5 of a core or more. JVM housekeeping (JIT compiler, GC threads)
is excluded — those burn CPU on purpose.

**Evidence.** The thread header line, annotated with the raw `cpu=`/`elapsed=` values
the number came from, and the top frame line.

**False positives.** A thread that spun hard during startup and has been idle since
still shows a high lifetime average. That is why the metric carries `basis`; trust
`delta` readings over single-dump ones.

---

_18 rules, rendered from `jvm-incident-agent 0.1.0 rules --format json` by scripts/render-rules.sh._
