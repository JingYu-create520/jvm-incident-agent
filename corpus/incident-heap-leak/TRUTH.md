# corpus/incident-heap-leak — ground truth

Real tool output from one JVM. Nothing hand-edited.

## How it was produced

```bash
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar demo-victim.jar --server.port=8182 > app.log 2>&1 &
# ramp the never-evicted cache until the collector hits the wall (the script stops at ~78 % used)
for i in $(seq 1 10); do
  curl -s "http://localhost:8182/victim/leak?mb=24&rows=50000" > /dev/null; sleep 2
done
# then small top-ups until the GC log really contains full collections
for i in $(seq 1 12); do
  [ "$(grep -c 'Pause Full' gc.log)" -ge 8 ] && break
  curl -s "http://localhost:8182/victim/leak?mb=8&rows=12000" > /dev/null; sleep 2
done
scripts/capture.sh -o corpus/incident-heap-leak -p <pid> -d 6 --gc-log work/gc.log --app-log work/app.log
```

Automated form: `scripts/generate-corpus.sh incident-heap-leak`.
Undo the incident with `curl -X DELETE http://localhost:8182/victim/leak`.

## What was planted

`LeakyCache` is an `ArrayList<Entry>` that is only ever appended to — no eviction, no size cap, no
expiry. Each 1 MB call chunk adds one `byte[1048576]` payload **plus** 50 000 decoded
`LeakyCache$CachedRow` objects (id `String`, value `String`, `byte[64]`), which is what a session
cache that stores both the serialized blob and its parsed form really retains.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `gc.log` | post-GC occupancy floor, monotone, never falls | `3M -> 121M -> 250M -> 250M -> 255M -> 255M` (of 256M) |
| `gc.log` | `Pause Full (G1 Compaction Pause)` summary lines | 13, all shaped `255M->255M(256M)` / `255M->253M(256M)` |
| `gc.log` | full GCs reclaim ~nothing (≤2M of 256M) | yes — the leak fingerprint |
| `gc.log` | max pause | 73.567 ms (`Pause Full`), vs 2-8 ms young pauses |
| `gc.log` | also present: 28 young pauses, 8 concurrent cycles, `G1 Preventive Collection` | — |
| `heap.histo` | `jmap -histo:live` runs a full GC first, so this is a **live-only** histogram | — |
| `heap.histo` | row 1 | `2144188 instances / 173252376 bytes  [B (java.base@17.0.5)` |
| `heap.histo` | row 2 | `1442468 / 34619232  java.lang.String` |
| `heap.histo` | row 3 | `700000 / 22400000  dev.jingyu.jia.victim.LeakyCache$CachedRow` |
| `heap.histo` | `Total 4454886 240286480` (229 MB live) | vs 12.9 MB in `corpus/healthy` |
| `app.log` | one `ERROR … dispatcherServlet … threw exception [Handler dispatch failed: java.lang.OutOfMemoryError: Java heap space] with root cause` | the request that tipped it over |
| `threads.dump` | 0 BLOCKED, no `victim-worker-`, 46 threads | clean |

## Rules that SHOULD fire

- **GCA003 (heap-leak fingerprint)** — post-GC floor climbs to 99.6 % of `Heap Max Capacity: 256M`
  and never drops after a collection. The raw lines are what make it unmissable:
  `GC(28) Pause Full (G1 Compaction Pause) 252M->252M(256M)`, `GC(46) … 255M->255M(256M)`. The rule
  reports the old-generation view of the same thing: `the old gen low-water mark is 217M, sits at 11
  of 13 collections with 88% of the heap still live after a Full GC`.
- **GCA001 (full-GC frequency)** — 13 Full GCs inside the first 4.7 s of uptime (GC(0)…GC(48)),
  consecutive, reclaiming 0 M. Verbatim: `13 Full GC collections inside 1.0 minute(s) (13.0/min,
  threshold 1.0), stopping the world for 551 ms in total, worst pause 74 ms`.
- **GCA004 (premature promotion)** — the copy-space failures are the leak pushing against the
  young generation: `11 collection(s) ran out of copy space (to-space exhausted / evacuation
  failure / promotion failed); 41 collection(s) triggered by humongous (direct-to-old) allocation;
  23 young collections reclaimed under 5% of the heap`. It ranks below GCA003 on purpose: the same
  evidence read without the live-set floor is the story of `corpus/incident-gc-storm`.
- **HIS001 (top consumers)** — verbatim: `[B holds 165.2 MB of 229.2 MB (72.1% of all bytes
  counted, 2,144,188 instances, 81 bytes each)`. Two million eight-byte payloads is the shape of a
  cache keyed per request; the app class `dev.jingyu.jia.victim.LeakyCache$CachedRow` is 700,000
  instances at rank 3, so the owner is nameable even if it is not yet provable.

## Rules that must NOT fire

- **TDA001/TDA002/TDA004** — 0 BLOCKED threads in either dump, and no `Java-level deadlock`
  trailer; the JVM is dying of occupancy, not of a lock.
- **TDA003/TDA005** — no name family with a counter grows between the dumps, and no pool is full of
  occupied workers; the thread count is the Spring Boot baseline.
- **TDA006 (thread burning CPU)** — nothing accumulates half a core between the two dumps; the Full
  GCs burn CPU inside the VM, and those pseudo-threads are excluded by name.
- **GCA002 (pause over SLA)** — worst pause 74 ms against a 200 ms SLA, on a heap small enough that
  one full compaction is 42-63 ms.
- **GCA005 (JVM configuration smell)** — `UseG1GC`, `Heap Region Size: 1M`, compressed oops on, no
  metaspace cap, `-Xms` = `-Xmx` = 256M. Nothing to complain about.
- **GCA006 (GC throughput below target)** — this is the one that could reasonably have fired:
  551 ms of stop-the-world inside 4.73 s of log is roughly 12 % of wall time. The rule refuses any
  window under 10 s, because a percentage over a flushed fragment of a startup says more about the
  buffering than about the JVM. Capture the same leak over a longer session and GCA006 will appear;
  `corpus/incident-gc-storm` is that capture.
- **GCA007 (allocation stalls)** — no `Allocation Stall` line appears in this log at all. That vocabulary belongs to ZGC: under G1 the same pressure is a copy-space failure, which is what GCA004 reads, and this rule would be reporting a collector that is not running here.
- **HIS002 (application class share)** — the leading non-JDK class is
  `dev.jingyu.jia.victim.LeakyCache$CachedRow` at 22,400,000 bytes, which clears the 8 MB absolute
  floor and misses the 10 % share floor at **9.32 %** of counted bytes. If you are reading this
  because you want to lower `MIN_SHARE`, note what holds it down: the 173 MB `[B` bag around it is
  72 % of the histogram, and a share is a ratio, not a measurement of the leak.
- **HIS003 (container count)** — the bar here is `max(50,000, totalInstances/20)` = 222,744 against
  `Total 4454886`, and the widest container row is `ConcurrentHashMap$Node` at 27,166. The
  700,000 `CachedRow` objects are not map nodes, and no `[Ldev.jingyu…;` array row appears in the
  histogram at all, which is the honest limit of this artifact: it names the victim, not the
  retainer. That is why the recommendation on GCA003 is a MAT dominator tree.
- **EXC001/EXC002/EXC003** — the log has exactly one throwable, an
  `ERROR … Servlet.service() … java.lang.OutOfMemoryError: Java heap space`, and 0 `Caused by:`.
  One occurrence cannot be a repeated cluster, a chain, or a burst.

## Honest caveats for rule tuning

- **GCA002 (long pauses)**: the slowest pause in this log is 73.567 ms. G1's full collection on a
  256 MB heap with 2 M live objects is simply not slower than that; a 200 ms or 1 s absolute
  threshold never fires on this corpus. Fire it relatively (≥10x the young-pause median, which is
  ~2 ms here) or accept that this corpus exercises GCA002 only weakly.
- **EXC002 (causal chain)** does not fire: an `OutOfMemoryError` has no `Caused by:`.
- The single OOME ERROR line is *part of the incident* (a real leaking app does throw it). Do not
  count it as an exception-cluster finding.
- `jmap -histo:live` forces one more Full GC, but `capture.sh` copies `gc.log` **before** the
  histogram, so that tool-induced collection is intentionally absent from this file.

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
