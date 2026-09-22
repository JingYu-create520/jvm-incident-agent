# corpus/incident-thread-leak — ground truth

Real tool output from one JVM. Nothing hand-edited.

## How it was produced

```bash
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar demo-victim.jar --server.port=8184 > app.log 2>&1 &
curl -s http://localhost:8184/victim/health          # x3, to warm the servlet pool
curl -s "http://localhost:8184/victim/leak-threads?count=80"   # <- incident part A
sleep 3
scripts/capture.sh -o corpus/incident-thread-leak -p <pid> -d 8 \
     --gc-log work/gc.log --app-log work/app.log \
     --between-cmd 'curl -s "http://localhost:8184/victim/leak-threads?count=60"'   # part B
```

Automated form: `scripts/generate-corpus.sh incident-thread-leak`.

The `--between-cmd` hook is what makes this a *two-dump* corpus: dump 1 is taken before the
second leak call and dump 2 after it, so a rule can measure growth instead of guessing from a
single snapshot.

## What was planted

`GET /victim/leak-threads?count=N` creates N raw `Thread`s named `victim-worker-<n>` with a
single global counter that only ever increases. Nothing calls `shutdown()`/`interrupt()`, nothing
is joined, and the list holding them is never cleared. Each thread parks by doing
`Thread.sleep(60_000)` in a loop, so it stays alive for the life of the JVM. This models the
classic "an executor is created per request and never shut down".

The two bursts are 80 and 60, and they are the *only* `victim-worker-` threads in the JVM.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `threads.dump` | lines matching `"victim-worker-` | **80** |
| `threads-2.dump` | lines matching `"victim-worker-` | **140** (60 more, taken seconds later) |
| both | highest ordinal | `victim-worker-1` … `victim-worker-140`, no gaps, no reuse |
| both | every worker is `TIMED_WAITING (sleeping)` at the identical frames | `java.lang.Thread.sleep(java.base@17.0.5/Native Method)` → `dev.jingyu.jia.victim.ThreadLeakService.lambda$leak$0(ThreadLeakService.java:40)` → `ThreadLeakService$$Lambda$938/0x….run(Unknown Source)` → `java.lang.Thread.run(java.base@17.0.5/Thread.java:833)` |
| `threads.dump` | state histogram | 84 `TIMED_WAITING (sleeping)`, 11 `WAITING (parking)`, 5 `TIMED_WAITING (parking)`, 2 `TIMED_WAITING (on object monitor)`, 1 `WAITING (on object monitor)`, **0 BLOCKED** |
| `threads.dump` | total `^"` header lines | 136 → 196 in dump 2 (includes ~56 VM-internal pseudo-threads, see caveats) |
| `app.log` | `WARN … ThreadLeakService : spawned 80 more victim-worker threads, 80 leaked so far` then `… spawned 60 more …, 140 leaked so far` | the two bursts, timestamped 12 s apart |
| `gc.log` | 0 Full GC, 4 young pauses, max pause 6.1 ms | boring |
| `heap.histo` | top consumer `[B` = 3.0 MB, `Total 301726 13065384` (12.5 MB live) | healthy-sized heap; the leak is threads, not bytes |

## Rules that SHOULD fire

- **TDA003 (thread leak)** — a numeric-suffix name prefix with 80 members in dump 1 growing to 140
  in dump 2, all alive at the same parking frame, and no pool lifecycle call anywhere near them.
  This is the rule that names the planted bug, and the hypothesis ranking puts `H-THREAD-LEAK`
  first because of it.
- **TDA005 (thread pool starvation)** — `All 80 workers of "victim-worker" are occupied and 80 of
  them sit in the same frame (dev.jingyu.jia.victim.ThreadLeakService#lambda$leak$0)`. That reads
  as a second opinion on the same fact, and it is true on its own terms: a family that is 100 %
  occupied and 100 % parked in one user frame cannot take work. An earlier version of this file
  listed TDA005 under "must NOT fire" on the reasoning that the planted bug is a leak, not a
  starved pool — but the rule never claims to know which of the two you planted, and the pool
  really is unusable. Note the asymmetry with TDA004 below: sleeping threads are not a *hotspot*
  and they are not *available*, and the two rules draw that line on purpose.

## Rules that must NOT fire

- **TDA001** — `Found one Java-level deadlock` appears 0 times.
- **TDA002/TDA004** — **0 BLOCKED threads**, so no contended monitor and no blocked-stack cluster.
  TDA004 additionally skips threads parked in `Thread.sleep` before it fingerprints anything, which
  is why 140 identical stacks do not make it a hotspot finding here.
- **TDA006 (thread burning CPU)** — every leaked worker is sleeping; none of them accumulates the
  half-a-core of CPU the rule wants.
- **GCA001-006** — 0 Full GC, 4 young pauses, max pause 6.1 ms, and the flushed window is 2.80 s
  of uptime, below the 10 s GCA006 needs before it will quote a throughput percentage at all.
- **HIS001/HIS002** — 12.5 MB live total, same as `corpus/healthy`; the leak is threads, not bytes.
- **HIS003 (container count)** — the widest container row is `ConcurrentHashMap$Node` at 28,339
  instances against the rule's `max(50,000, totalInstances/20)` = 50,000 floor.
- **EXC001/EXC002/EXC003** — 0 ERROR lines, 0 `Caused by:`, 0 throwables — the only log noise is
  the two WARN bookkeeping lines.

## Honest caveats for rule tuning

- **Threshold vs framework noise.** The always-on `ResidentWorkers` pool contributes
  `victim-heartbeat-1..2`, `victim-request-worker-1..4` and `victim-idle-scanner`, and Tomcat
  contributes `http-nio-8184-exec-1..10`, `http-nio-8184-Poller`, `http-nio-8184-Acceptor`,
  `Catalina-utility-1..2`. A prefix-with-counter heuristic that fires at, say, 10 members would
  light up on Tomcat's pool in *every* folder including `corpus/healthy`. The planted signal here
  is 80→140 on one prefix; anything below ~50 per prefix risks a false positive on framework pools.
- Do not count `^"` lines as threads: 21 of the 136 header lines in dump 1 are VM-internal
  (`"GC Thread#0..15"`, `"G1 Conc#0..2"`, `"VM Thread"`, `"Attach Listener"`, `"DestroyJavaVM"`, …)
  and have no `java.lang.Thread.State:` line. The real Java thread counts are 115 (dump 1) and
  175 (dump 2), which the SMR `length=` agrees with; the deadlock folder has 46 and
  `corpus/healthy` 35.
- `"victim-worker-N" #<id> daemon prio=5 … elapsed=<t>s` — the `#<id>` is the JVM-generated thread
  number (57…196 here) and is *not* the `N` in the name. Correlate on the name, not the `#`.
- Sleeping threads show `waiting on condition` in the header line and
  `TIMED_WAITING (sleeping)` as their state; a rule that only matches `parking` will miss them.

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
