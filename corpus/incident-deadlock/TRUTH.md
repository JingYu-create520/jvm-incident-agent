# corpus/incident-deadlock — ground truth

Everything in this folder is real tool output from one JVM. Nothing was hand-edited.

## How it was produced

```bash
mkdir -p work && cd work
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar ../demo-victim/target/demo-victim.jar --server.port=8182 > app.log 2>&1 &
curl -s http://localhost:8182/victim/health          # warm the servlet stack, 3x
curl -s "http://localhost:8182/victim/healthy?iterations=200"
curl -s http://localhost:8182/victim/deadlock        # <- the incident
sleep 6
scripts/capture.sh -o corpus/incident-deadlock -p <pid> -d 6 --gc-log work/gc.log --app-log work/app.log
```

Automated form: `scripts/generate-corpus.sh incident-deadlock`.

## What was planted

Two threads take the same two intrinsic monitors in opposite order and stay there forever:

| thread | holds | waits for |
|---|---|---|
| `victim-ledger-poster` | `accountMutex` (`0x00000000ff6309a0`) | `ledgerMutex` (`0x00000000ff6309b0`) |
| `victim-statement-writer` | `ledgerMutex` (`0x00000000ff6309b0`) | `accountMutex` (`0x00000000ff6309a0`) |
| `victim-report-exporter` | `reportMutex` (`0x00000000ff6309c0`) | - (sleeps 30 min while holding it) |
| `victim-report-fetcher-1..8` | nothing | `reportMutex` (`0x00000000ff6309c0`) |

A second, independent plant makes *contention* (not a cycle) visible: `victim-report-exporter`
enters `reportMutex` and then sleeps for 30 minutes while holding it, and
`victim-report-fetcher-1..8` all block on that same monitor object.

`synchronized` on plain `Object`s is deliberate: HotSpot's deadlock detector only reports
intrinsic-monitor cycles, so a `ReentrantLock` version would not appear in `jstack` at all.

The JVM also carries 7 always-on worker threads (`victim-heartbeat-1..2`,
`victim-request-worker-1..4`, `victim-idle-scanner`) plus the Tomcat pool, so the dump looks like
a live service rather than an empty VM.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `threads.dump`, `threads-2.dump` | `Found one Java-level deadlock` | 1 occurrence each, identical in both dumps (permanent) |
| both dumps | `Found 1 deadlock.` trailer | present |
| both dumps | deadlock cycle names + `waiting to lock monitor 0x… (object 0x…, a java.lang.Object), which is held by "…"` | present |
| `threads.dump` | `java.lang.Thread.State: BLOCKED (on object monitor)` | 10 threads (2 cycle + 8 waiters) |
| `threads.dump` | `- waiting to lock <0x00000000ff6309c0> (a java.lang.Object)` | 8 identical frames at `DeadlockPlant.fetchReport` |
| `threads.dump` | `- locked <0x…>` ownership edges for the cycle | present on both cycle threads |
| state histogram | 10 BLOCKED / 12 RUNNABLE / 12 WAITING / 12 TIMED_WAITING | — |
| `app.log` | one `WARN … DeadlockPlant : deadlock planted round=1 …` line | no ERROR lines |
| `gc.log` | 4 young pauses, 2 concurrent cycles, **0** `Pause Full`, max pause 8.0 ms | boring |
| `heap.histo` | top consumer `[B` = 3 126 320 B (3.1 MB) of `Total 302963 13103336` (13.1 MB live) | no heap problem |

## Rules that SHOULD fire

- **TDA001 (deadlock)** — the VM itself reports the cycle; both dumps must agree.
- **TDA002 (lock contention)** — 8 threads BLOCKED on one monitor object, with an identifiable
  owner (`victim-report-exporter`, which is itself WAITING/sleeping while holding it).
- **TDA004 (blocked-stack hotspots)** — `DeadlockPlant.fetchReport(DeadlockPlant.java:135)` is the
  identical top frame of 8 BLOCKED threads; the two cycle threads add 2 more BLOCKED fingerprints.
- **TDA005 (pool starvation)** — all 8 workers of `victim-report-fetcher` are occupied, and they are
  occupied *by* the deadlock. Starving that pool is a consequence of what was planted here, not a
  separate condition; an earlier version of this file listed TDA005 under "must NOT fire", which the
  capture itself contradicts.

## Rules that must NOT fire

- **TDA003 (thread leak)** — no repeating `victim-worker-` prefix: 0 occurrences. The families here
  are 8 `victim-report-fetcher-N` and the Spring Boot baseline, and neither grows between dumps 1
  and 2.
- **TDA006 (thread burning CPU)** — the four cycle threads and the eight waiters are all on a
  monitor, so none of them accumulates the half-core the rule needs to call anything hot. A
  deadlock is invisible to CPU and unmistakable in a dump, which is the whole reason both signal
  families exist.
- **GCA001-006** — no Full GC in this log. GCA006 also abstains on window: the flushed part covers
  2.78 s of uptime and the rule refuses to divide pause time by anything under 10 s.
- **HIS001/HIS002** — 13.1 MB live total, the same shape as `corpus/healthy`. Two threads holding
  two locks do not show up in a histogram.
- **HIS003 (container count)** — the widest container row is `ConcurrentHashMap$Node` at 28,410
  instances, under the `max(50,000, totalInstances/20)` = 50,000 floor (`Total 300211`).
- **EXC001/EXC002/EXC003** — no ERROR lines and no `Caused by:` in `app.log`: the deadlock was
  planted inside a request that never returned, so nothing was ever logged to fail.

## Parser notes (shapes that are easy to get wrong)

- The dump has a `Threads class SMR info:` preamble with `_java_thread_list=0x…, length=46,
  elements={…}` and a `_to_delete_list={…}` block of bare hex lines **before** the first thread.
- After the Java threads, the dump lists VM-internal threads that are **quoted like threads but
  have no `java.lang.Thread.State:` line**: `"GC Thread#0"…"GC Thread#15"`, `"G1 Main Marker"`,
  `"G1 Conc#0..2"`, `"G1 Refine#0"`, `"G1 Service"`, `"VM Thread"`, `"Attach Listener"`,
  `"DestroyJavaVM"`, `"Common-Cleaner"`, `"Cleaner-0"`, `"Notification Thread"`,
  `"Reference Handler"`, `"Finalizer"`, `"Signal Dispatcher"`, `"C1/C2 CompilerThread0"`,
  `"Service Thread"`, `"Sandbox Thread"`. `grep -c '^"'` here returns 71 while the real Java
  thread count is 46.
- The deadlock section repeats thread names (`victim-ledger-poster` appears 3x: once as a thread
  header, twice inside the deadlock report) — do not double-count it as threads.
- Frame lines carry the module suffix: `java.lang.Thread.run(java.base@17.0.5/Thread.java:833)`
  and lambda frames read `DeadlockPlant$$Lambda$941/0x000000010053e490.run(Unknown Source)`.
- `-l` adds `Locked ownable synchronizers:` to every thread (usually `- None`).
- The first line of each dump is a local timestamp (`2026-09-20 21:37:05` in `threads.dump`,
  `2026-09-20 21:37:13` in `threads-2.dump` — 8 s apart) and the second is
  `Full thread dump OpenJDK 64-Bit Server VM (17.0.5+1-b653.25 mixed mode):`. Use the `elapsed=…s`
  on each thread header, not that line, when correlating the two dumps.
- **A BLOCKED thread's reported line points *inside* the block, not at `synchronized (…)`**:
  `withdrawThenPost` is at `DeadlockPlant.java:106`, the first statement inside
  `synchronized (ledgerMutex) {` opened on line 105; `fetchReport` is at `:135` inside the block
  opened on 134. Locate "blocked entering which monitor" from the `- waiting to lock` line, never
  by matching a source line against a `synchronized` keyword.

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
