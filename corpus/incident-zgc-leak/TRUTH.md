# corpus/incident-zgc-leak — ground truth: a heap leak on a collector with no Full GC

Real tool output from one JVM, nothing hand-edited. This folder exists because the corpus had no
ZGC capture, and the absence was hiding three defects at once — see "What this folder caught".

## How it was produced

```bash
java -XX:+UseZGC -Xmx1g -Xms1g -XX:+HeapDumpOnOutOfMemoryError \
     -Xlog:gc*:file=live/gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 \
     -jar demo-victim/target/demo-victim.jar --server.port=18081 > live/app.log 2>&1 &
for i in $(seq 1 14); do
  curl -s "http://127.0.0.1:18081/victim/leak?mb=96" >/dev/null
  sleep 2
done
scripts/capture.sh -o corpus/incident-zgc-leak -d 6 --gc-log live/gc.log --app-log live/app.log
```

JDK: the same JBR 17.0.5 the rest of the corpus was captured with, started with `-XX:+UseZGC`.
ZGC is not experimental on 17, so no `-XX:+UnlockExperimentalVMOptions` was needed; the heap is
1 GB because ZGC on 17 wants room to lay out its stripes.

Automated form: `scripts/generate-corpus.sh incident-zgc-leak` (set `CORPUS_DIR` somewhere else
first unless you mean to replace this folder — the default output directory is `corpus/`). A re-run
does not reproduce the numbers: the run made to check this recipe stopped at 194 cycles and 2
stalls, where this committed capture ran to 278 and 9. What must reproduce is the
conclusion — `GCA003` first, `Allocation Stall` lines present, `Pause Full` absent — and it did.

No `--between-cmd`: the leak itself grew between the two dumps, which is the point of the run.

## What was planted

`GET /victim/leak?mb=N` appends N megabytes of `byte[]` chunks to an unbounded `LeakyCache` and
never removes them — the same endpoint `corpus/incident-heap-leak` uses, on a G1 heap with a
quarter of the memory and a collector that never stops the world. Fourteen calls, 2 seconds apart.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `gc.log` | `[gc] GC(n) Garbage Collection (<cause>) A->B` cycle summaries | **278**, spanning uptime 0.010 s – 48.816 s |
| `gc.log` | `Pause Full` | **0** — ZGC has no Full GC; this is the whole reason the folder exists |
| `gc.log` | post-cycle occupancy on the last ten cycles | `1012M(99%)->1014M(99%)`, `1014M(99%)->1012M(99%)` — pinned, not draining |
| `gc.log` | `Max Capacity: 1024M(100%)` | the denominator ZGC prints as percentages instead of a bracketed capacity |
| `gc.log` | `Allocation Stall (<thread name>) <ms>` | **9** events, longest 38.519 ms, on `http-nio-18081-exec-4/8/10` |
| `gc.log` | `[gc,phases] Pause Mark Start 0.007ms` and friends | three STW phases per cycle, ~0.03 ms in total per cycle |
| `gc.log` | `[gc,stats]` rolling-average tables | 240 rows shaped like `Collector: Garbage Collection Cycle  30.142 / 613.231 … ms` — statistics, not events |
| `heap.histo` | rank 1 | `46267 instances / 973849728 bytes  [B` = 928.7 MB of the 939.7 MB counted (98.8 %) |
| `heap.histo` | the leaking class | `dev.jingyu.jia.victim.LeakyCache$Entry` — 926 instances, 51,856 bytes: the *entry* is small, the payload it holds is not |
| `heap.histo` | `Total 254523 985307672` | against `Total 298054 12906984` in `corpus/healthy` — same object count, 76× the bytes |
| `threads.dump` | header lines matching `^"` | 56 in both dumps; ZGC's own `ZWorker#0..3` and `RuntimeWorker#0..9` are quoted like threads and have no `java.lang.Thread.State:` line and no frames |
| `threads.dump` | `BLOCKED`, `Found one Java-level deadlock` | **0**, 0 |
| `app.log` | `ERROR` | 5 (the `OutOfMemoryError: Java heap space` the leak reaches), 0 `Caused by:` |

## What this folder caught

Three rules were wrong on this capture before any of it was written down here, and two of the
three errors are the kind that make a report untrustworthy rather than merely incomplete:

1. **TDA005 reported a starved pool of four.** `"ZWorker#0" … runnable` has no stack, so the four
   ZGC workers were "all occupied and all sitting in the same frame", and the frame was printed as
   `<empty stack>`. It was the top hypothesis of the run. Fixed by `JThread.isVmWorker()`: a header
   line with no frames, no monitors and no ownable synchronizers is not a Java thread and never
   enters a name-family grouping.
2. **GCA006 reported 63.4 % of wall time in stop-the-world pauses.** The `[gc,stats]` tables print
   rolling averages per collection, and a `… / 613.231 … ms` cell was being read as that
   collection's pause. The real stop-the-world total was about 10 ms out of 48.8 s, which is the
   entire reason someone runs ZGC. Fixed by ignoring statistics rows and by separating a record's
   summary duration from its phase durations and its concurrent durations.
3. **GCA003 and GCA001 said nothing about a heap pinned at 99 %.** Both were reading
   `Kind.FULL`, which ZGC never emits. `GcLog.isMajor(kind)` now maps the whole-heap concurrent
   cycle to "major" for ZGC and Shenandoah, so the live-set floor is visible: 20M → 1012M of
   1024M, reported as GCA003 CRITICAL. `Allocation Stall` became a first-class event (GCA007).

## Rules that SHOULD fire

- **GCA003 (heap-leak fingerprint)** — `Across 278 major collections the heap low-water mark
  climbed from 20M to 1012M (+4960%), climbed and is now pinned against the ceiling (capacity
  1024M)`. This is the same planted bug as `corpus/incident-heap-leak`, and the report ranks it
  first here too, which is the point: the conclusion did not depend on the collector stopping the
  world to be true.
- **GCA001 (full-GC storm)** — `278 whole-heap concurrent cycles inside 1.0 minute(s) (278.0/min,
  threshold 1.0), stopping the world for 10 ms in total, worst pause 0 ms`. Read the rate, not the
  pause column: the rule's title says Full GC storm because that is the shape it was written for,
  while the sentence names what this collector actually runs. 10 ms of stopping across 278
  collections is ZGC doing its job; doing it 278 times a minute is the heap having no room left.
- **GCA007 (allocation stalls)** — `9 allocation stall(s), longest 39 ms, affecting:
  http-nio-18081-exec-8, http-nio-18081-exec-4, http-nio-18081-exec-10`. The thread names are in
  the log, and they are the request threads: it is latency that is being paid here.
- **HIS001 (top consumers)** — `[B holds 928.7 MB of 939.7 MB (98.8% of all bytes counted, 46,267
  instances, 21048 bytes each)`. The histogram was taken with `jmap -histo:live`, which under ZGC
  triggers a cycle rather than a Full GC, so the leak survives it.

## Rules that must NOT fire

- **TDA001/TDA002/TDA004** — 0 BLOCKED threads, no `Found one Java-level deadlock`; nothing is
  waiting on a lock, the JVM is waiting on memory.
- **TDA003/TDA005** — no application thread family grows and no application pool is full; the only
  counted families here belong to Tomcat, and the ZGC workers are excluded as VM workers (see
  "What this folder caught", item 1).
- **TDA006 (thread burning CPU)** — nothing reaches half a core between the two dumps; ZGC's
  concurrent work is done by the workers this folder just excluded.
- **GCA002 (pause over SLA)** — the longest stop-the-world phase in the log is far under the 200 ms
  SLA, and the longest allocation stall is 38.519 ms, which is GCA007's sentence, not this one.
- **GCA004 (premature promotion)** — no humongous allocation cause and no to-space exhausted:
  ZGC has no young/old promotion to fail, which is exactly why this folder cannot use that rule to
  tell a leak from an allocation storm and must lean on GCA003 instead.
- **GCA005 (JVM configuration smell)** — the log's own init lines show `-XX:+UseZGC`, a 1 GB heap
  with `-Xms` = `-Xmx`, and no metaspace cap, and no `GCLocker Initiated GC` appears anywhere.
- **GCA006 (GC throughput below target)** — about 10 ms of stop-the-world across 48.8 s of log is
  0.02 %. This is the number the statistics-table bug inflated to 63.4 %, so it is recorded here
  on purpose: the correct answer for a ZGC heap in trouble is not a throughput finding.
- **HIS002 (application class share)** — the leading non-JDK class is `LeakyCache$Entry` at
  51,856 bytes, 0.005 % of counted bytes: the leak's *handles* are tiny and its payload is
  `byte[]`, so HIS001 fires and this one cannot.
- **HIS003 (container count)** — the widest container row is `ConcurrentHashMap$Node` at 27,124
  against `max(50,000, totalInstances/20)` = 50,000 on `Total 254523`.
- **EXC001/EXC002/EXC003** — 5 ERROR lines and 0 `Caused by:`, but only 2 distinct parsed stacks:
  the OOME arrives both with and without a trace, so no cluster reaches 3 and there is no chain to
  attribute. A missing-heap incident that never logs a stack is still an incident, which is why
  the GC rules, not the exception rules, carry this folder.

## Honest caveats for rule tuning

- The GC log covers the whole session here (48.8 s, 278 cycles), unlike the quiet folders where
  block buffering cut it at 2-8 s. `Allocation Stall` lines are the exception to that: a stall is
  printed when it ends, so the count in this file is what had finished before the copy was taken.
- `worst pause 0 ms` in GCA001's sentence is formatting, not absence: the worst cycle pause is
  0.03 ms and the report rounds to whole milliseconds.
- If ZGC is asked to explain itself with `-Xlog:gc+stats=debug`, the statistics rows grow; they are
  skipped by shape (`Label: … a / b`) rather than by tag, because the tag that carries them is
  `[gc,stats]` and that tag also legitimately carries things a reader may want counted later.
