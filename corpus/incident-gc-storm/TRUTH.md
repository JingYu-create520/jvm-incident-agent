# corpus/incident-gc-storm — ground truth

Real tool output from one JVM. Nothing hand-edited.

## How it was produced

```bash
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar demo-victim.jar --server.port=8182 > app.log 2>&1 &
# async on purpose: the storm must still be running while the artifacts are grabbed
curl -s "http://localhost:8182/victim/gcstorm?rounds=2000&workingSetMb=175"
sleep 4
scripts/capture.sh -o corpus/incident-gc-storm -p <pid> -d 6 \
     --gc-log work/gc.log --app-log work/app.log \
     --between-cmd 'curl -s "http://localhost:8182/victim/healthy?iterations=4000&payload=32768"'
```

Automated form: `scripts/generate-corpus.sh incident-gc-storm`.

## What was planted

A batch job (`victim-batch-1`, daemon thread) that:

1. loads a ~175 MB working set of `byte[700_000..3_100_000]` arrays. On a 256 MB G1 heap
   `Heap Region Size: 1M`, so every array > 512 KB is **humongous**: allocated directly into
   contiguous old-gen regions;
2. then re-reads 5 % of that batch per round (allocate new, drop old) plus short-lived
   `String.toUpperCase()`/`char[]` copies, sleeping 25 ms per round;
3. catches `OutOfMemoryError` per round and trims 10 % of its own working set instead of dying.

The live set is **flat by construction** and is released in a `finally` block when the batch
ends. That is what separates this capture from `incident-heap-leak`: the collector is drowning,
the heap is not.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `gc.log` | `Pause Full (G1 Compaction Pause)` summary lines | 8, all within the first 0.6 s of the storm window |
| `gc.log` | full-GC shape: heap near the ceiling, nothing reclaimed | `236M->236M(256M)`, `240M->235M(256M)`, `226M->226M(256M)` |
| `gc.log` | young pauses | 1886 events: `(Normal) (G1 Evacuation Pause)`, `(Concurrent Start) (G1 Humongous Allocation)`, `(Normal) (G1 Humongous Allocation)` |
| `gc.log` | concurrent mark cycles | 660 events, plus their `Pause Remark` / `Pause Cleanup` pairs |
| `gc.log` | max pause | 16.774 ms |
| `gc.log` | size | 4.7 MB / 43 577 lines — this is what ~20 s of a real G1 storm at `-Xlog:gc*` costs |
| `heap.histo` | live top consumer at capture time (mid-batch) | `43794 instances / 181421528 bytes  [B` — 96 % of all live bytes, 68 % of the 256 M heap |
| `heap.histo` | `Total 252542 189568320` (181 MB live) vs 12.9 MB in `corpus/healthy` | the batch working set, released when the batch ends |
| `threads.dump` | `"victim-batch-1"` RUNNABLE at `java.lang.String.toUpperCase` → `StringLatin1.toUpperCaseEx` → `GcStormService.runBatch` | the allocating thread is identifiable in the dump |
| `threads.dump` | 0 BLOCKED, no `victim-worker-` | clean |
| `app.log` | `INFO … GcStormService : batch 1 loaded working set 175 MB across 97 humongous arrays`, `batch 1 round N churned XXXX MB heapUsedMb 2xx` | no ERROR lines |

## Rules that SHOULD fire

- **GCA001 (full-GC frequency)** — 8 Full GCs inside ~4 s of the storm window, and 1886 young
  collections inside ~20 s: whichever rate the rule uses, this is the pathological log. Verbatim:
  `8 Full GC collections inside 1.0 minute(s) (8.0/min, threshold 1.0), stopping the world for
  99 ms in total, worst pause 17 ms`.
- **GCA004 (premature promotion)** — read it from `[gc,heap]`, and note the trap: the batch's
  175 MB never shows up as `Old regions` (that peaks at only `0->16`), because arrays larger than
  half a region are accounted as **`Humongous regions: 228->228`** (peak 230, stable all storm). A promotion rule that watches
  only `Old regions` will report nothing here; it has to count humongous regions too (or use the
  `217M-236M of 256M` post-pause floor). Verbatim: `1894 collection(s) triggered by humongous
  (direct-to-old) allocation; 1880 young collections reclaimed under 5% of the heap`.
- **GCA006 (GC throughput below target)** — `Over 22 s of log, 7.9% of wall time was spent in
  stop-the-world pauses (1737 ms across 2548 pauses)`. This is the finding that decides whether the
  Full GC count means anything at all. The `2548` is parsed events (a concurrent-mark cycle is one
  event each); the pause sum counts only lines that stopped the application — under 0.1.2 this
  reading was `48.6% … (10694 ms across 2555 pauses)`, because a cycle's concurrent duration was
  being added to the total. See `corpus/incident-zgc-leak` for how that was found.
- **HIS001 (top consumers)** — `[B` holds 173.0 MB of 180.8 MB counted, 43,794 instances averaging
  4143 bytes (95.7 % of counted bytes). This histogram was taken mid-batch, so the batch is
  visible; if you re-capture after the batch ends, it disappears.
- **GCA005 (JVM configuration smell)** — fires on a line the JVM wrote itself,
  `Pause Young (Normal) (GCLocker Initiated GC)`, not on a heuristic. It is MEDIUM and it is
  deliberately not the top hypothesis: the rule reports that JNI critical sections are forcing
  collections, which is real and worth knowing, and it is *not* where the 48.6 % went. An earlier
  version of this file had GCA005 under "must NOT fire", which the log contradicts — the same
  mistake `corpus/incident-deadlock` records for TDA005, in the opposite direction.

## Rules that must NOT fire

- **TDA001-003** — 0 BLOCKED threads, no `Found one Java-level deadlock`, and the only busy Java
  thread is the single `victim-batch-1`.
- **TDA004/TDA005** — no cluster of threads sharing a frame, and no name family with a counter
  behind it; the storm is one thread and a collector, not a pool.
- **TDA006 (thread burning CPU)** — the batch thread allocates rather than computes; nothing
  reaches the half-a-core floor the rule needs between the two dumps.
- **GCA002 (pause over SLA)** — max pause 16.774 ms against a 200 ms SLA. On a 256 MB heap an
  absolute pause threshold is unreachable, which is exactly why the rate and throughput rules
  exist instead of relying on one.
- **GCA003 (heap-leak fingerprint)** — the discrimination test of this folder, see the caveats
  below. Post-GC occupancy oscillates in a 217M-236M band instead of climbing and staying there.
- **GCA007 (allocation stalls)** — no `Allocation Stall` line appears in this log at all. That vocabulary belongs to ZGC: under G1 the same pressure is a copy-space failure, which is what GCA004 reads, and this rule would be reporting a collector that is not running here.
- **HIS002 (application class share)** — the largest non-JDK row in this histogram is
  `ch.qos.logback.classic.Logger` at 15,792 bytes. Nothing comes near the 8 MB absolute floor,
  because the bytes are in `[B`, and that is HIS001's sentence to say.
- **HIS003 (container count)** — the widest container row is `ConcurrentHashMap$Node` at 27,829
  instances against a floor of `max(50,000, totalInstances/20)` = 50,000 on `Total 252542`. The
  batch is arrays, not map entries.
- **EXC001/EXC002/EXC003** — 0 ERROR lines, 0 `Caused by:`, and 0 throwables to cluster in a
  minute; the one OOME backoff appears only as a one-line WARN with no stack trace,
  `batch 1 hit the heap ceiling at round 12, trimmed to 168 MB`.

## Honest caveats for rule tuning

- **GCA003 (heap-leak fingerprint) is the discrimination test of this folder.** Post-GC occupancy
  here is *flat* (217-236 M band, oscillating), not monotone, and the log ends with the batch
  releasing its set. A leak rule that fires on "high occupancy" alone will fire here and be
  wrong; it must look for a monotone floor that survives consecutive Full GCs (compare
  `incident-heap-leak`, where the floor is pinned at 255M->255M).
- **GCA002 (long pauses)**: max 16.774 ms. Same story as the heap leak — an absolute 200 ms/1 s
  threshold is unreachable on a 256 MB heap. Relative thresholds do fire (young median ~1 ms).
- `Pause Full (G1 Compaction Pause)` is the only Full-GC reason string G1 emits here; there is no
  `(G1 Humongous Allocation)` Full GC — humongous pressure shows up as the *cause* on
  `Pause Young (… Humongous Allocation)` lines and as `(G1 Preventive Collection)` in the leak log.

## GC log completeness (a JDK buffering reality, not a corpus defect)

`-Xlog:...:file=` output is block-buffered inside the JVM, so a copy taken from a *running* JVM
contains only what had been flushed. The quiet folders here therefore cover roughly the first
2.6-8.1 s of a 13-26 s session, while the two pressure folders cover their whole incident (the
heap leak wrote 84 KB and the storm 4.7 MB, so the buffer kept up). `capture.sh` copies the file
as it finds it; the rest reaches disk when the buffer fills or when the JVM shuts down cleanly -
`taskkill /F`/`kill -9` never flushes. Measured coverage of the four `gc.log` files in this corpus:

| folder | gc.log covers | lines |
|---|---|---|
| `incident-exceptions` | uptime 0 - 2.62 s | 125 |
| `incident-deadlock` | uptime 0 - 2.78 s | 125 |
| `incident-thread-leak` | uptime 0 - 2.80 s | 125 |
| `healthy` | uptime 0 - 8.12 s | 140 |
| `incident-heap-leak` | uptime 0 - 4.73 s (the entire incident: 13 Full GCs by GC(48)) | 809 |
| `incident-gc-storm` | uptime 0 - 22.53 s (the entire storm: 1886 young pauses, 8 Full GCs) | 43 577 |

No planted evidence lives in the unflushed tail, and every GCA-relevant statement in this file is
made about the window above.
