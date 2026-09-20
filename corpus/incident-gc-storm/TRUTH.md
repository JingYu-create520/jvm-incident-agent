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
  collections inside ~20 s: whichever rate the rule uses, this is the pathological log.
- **GCA004 (premature promotion)** — read it from `[gc,heap]`, and note the trap: the batch's
  175 MB never shows up as `Old regions` (that peaks at only `0->16`), because arrays larger than
  half a region are accounted as **`Humongous regions: 228->228`** (peak 230, stable all storm). A promotion rule that watches
  only `Old regions` will report nothing here; it has to count humongous regions too (or use the
  `217M-236M of 256M` post-pause floor).
- **HIS001 (top consumers)** — `[B` = 173 MB of the live histogram (this histogram was taken
  mid-batch, so the batch is visible; if you re-capture after the batch ends, it disappears).

## Rules that must NOT fire

TDA001-005 (no blocked threads at all), EXC001/EXC002 (0 ERROR lines, 0 `Caused by:`; the one OOME backoff
appears only as a one-line WARN with no stack trace, `batch 1 hit the heap ceiling at round 12, trimmed to 168 MB`), GCA005.

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
