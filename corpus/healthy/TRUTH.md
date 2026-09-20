# corpus/healthy — ground truth: the analyzer must report ZERO findings here

This is the negative control of the corpus. Real tool output from one JVM, nothing hand-edited.
If any of the 14 rules fires on this folder, the rule is wrong, not this folder.

## How it was produced

```bash
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar demo-victim.jar --server.port=8186 > app.log 2>&1 &
curl -s http://localhost:8186/victim/health
for i in 1 2 3 4; do
  curl -s "http://localhost:8186/victim/healthy?iterations=2500&payload=16384"
  sleep 2
  curl -s http://localhost:8186/victim/health
done
scripts/capture.sh -o corpus/healthy -p <pid> -d 6 --gc-log work/gc.log --app-log work/app.log
```

Automated form: `scripts/generate-corpus.sh healthy`.

## What this mode does — and deliberately does not do

`GET /victim/healthy` allocates a fresh 16 KB `byte[]` per iteration, fills it, wraps it in a
`String`, reads one element and drops it. Bounded loop, no shared mutable state, no monitor
entered, no thread created, nothing retained past the return. `/victim/health` is read-only
management-bean introspection. No incident endpoint was called on this JVM.

The JVM does run the 7 always-on `ResidentWorkers` threads in every mode (they exist in the
healthy dump too) — they are 2 sleeping heartbeats, 4 queue-parking pool workers and 1 periodic
scanner, and they never multiply.

## Measured content of the four artifacts

| artifact | measured |
|---|---|
| `threads.dump` / `threads-2.dump` | 35 real Java threads (SMR `length=35`; `grep -c '^"'` says 56 because 21 lines are VM-internal pseudo-threads). States: 12 RUNNABLE, 4 TIMED_WAITING (sleeping), 5 TIMED_WAITING (parking), 2 TIMED_WAITING (on object monitor), 11 WAITING (parking), 1 WAITING (on object monitor). **0 BLOCKED. `deadlock` appears 0 times. `waiting to lock` appears 0 times.** |
| thread names | `victim-heartbeat-1..2`, `victim-request-worker-1..4`, `victim-idle-scanner` (max 4 members on any `victim-` prefix), plus framework pools: `http-nio-8186-exec-1..10`, `http-nio-8186-Poller/Acceptor`, `Catalina-utility-1..2`, JIT/GC/VM threads. **0 `victim-worker-` threads.** |
| `gc.log` | 7 GC events across GC(0)…GC(6), in the flushed first 8.1 s of a ~25 s session: `GC(0) 23M->2M(256M) 2.081ms`, `GC(1) 34M->4M(256M) 4.124ms`, `GC(2) … (Metadata GC Threshold) 16M->5M(256M) 3.564ms`, `GC(3)` and `GC(5)` = `Concurrent Mark Cycle` (5.08 ms / 5.28 ms) with their `Pause Remark`/`Pause Cleanup` pairs, `GC(4) … (Metadata GC Threshold) 110M->11M(256M) 8.219ms`, `GC(6) 152M->13M(256M) 8.425ms`. **0 `Pause Full`, 0 `to-space exhausted`, 0 `allocation fail`.** Post-GC floor stays ≤13M of 256M (5 %). Max pause anywhere: **8.425 ms**. |
| `heap.histo` | `jmap -histo:live`, live only: `Total 298054 12906984` (12.3 MB). Row 1 is `[B` with **3 107 072 bytes (3 MB, 2.6 % of a 256 MB in-use heap, 24 % of live bytes)**, then `java.lang.String` 1.1 MB, `ConcurrentHashMap$Node` 0.9 MB, `java.lang.Class` 0.88 MB. No application class in the top 20. |
| `app.log` | 17 lines, **all `INFO`**, zero `ERROR`/`WARN`, zero `Caused by:`, zero stack traces. Startup banner-free Boot log + 4 `heartbeat from victim-heartbeat-N heapUsedMb=…` lines + the DispatcherServlet init trio. |

## The contract, rule by rule

| rule | must report | why |
|---|---|---|
| TDA001 | nothing | no deadlock section, no `waiting to lock` at all |
| TDA002 | nothing | 0 BLOCKED threads; the 3 `…(on object monitor)` threads are `Reference Handler`/`Finalizer`-style `Object.wait()` on their own queue, which is idle, not contention |
| TDA003 | nothing | no name prefix with a counter exceeds 10 members, and the only ≥10 group is Tomcat's own `http-nio-*-exec-N` |
| TDA004 | nothing | no blocked frames to fingerprint |
| TDA005 | nothing | every pool thread is idle-parked on an empty queue; nothing is queued, nothing is starved |
| GCA001 | nothing | 0 full collections |
| GCA002 | nothing | max pause 8.4 ms; mean of the 5 young pauses is 5.28 ms |
| GCA003 | nothing | post-GC occupancy oscillates 2M-13M, no monotone climb, and every collection reclaims ≥90 % of the delta |
| GCA004 | nothing | `Humongous regions: 0->0` on every event, and `Old regions` ends at `0->3` over the whole session (class/static data, not churn) |
| GCA005 | nothing | `Compressed Oops: Enabled (32-bit)`, `Heap Region Size: 1M`, no `-XX:MaxMetaspaceSize`, heap min = initial = max = 256M (a deliberately sane config) |
| HIS001 | nothing | top consumer is `[B` at 3 MB — that is the normal state of *every* Java process; a byte-array-is-big finding here would also fire on all 5 incident folders |
| HIS002 | nothing | same as above, ranked on shallow size |
| EXC001 | nothing | no throwable in the log |
| EXC002 | nothing | no `Caused by:` |

## Traps this folder intentionally contains

These strings *do* appear in `corpus/healthy` and must not be mistaken for findings:

1. **`(Metadata GC Threshold)`** on 2 of the 7 young collections — normal metaspace-driven
   collection during Spring startup. A GCA005 rule keyed on that phrase fires on healthy.
2. **`[B` is the top histogram row** in healthy *and* in every incident folder. HIS001/HIS002 need
   an absolute or share-of-live threshold (e.g. ≥64 MB or ≥30 % of live bytes), not "byte arrays
   exist".
3. **`http-nio-8186-exec-1` … `exec-10`** — 10 same-prefix threads with a counter. Any thread-leak
   threshold at or below 10 names this JVM's servlet pool.
4. **`victim-heartbeat-1`, `victim-request-worker-1..4`** — the app's own prefix scheme exists in
   the healthy baseline; "prefix matches `victim-`" is not a signal, growth is.
5. **`heapUsedMb=86` in `app.log`** while `heap.histo` totals 12.3 MB live. Eden garbage is
   normally 5-15x the live set — a heap-growth finding must come from the histogram or from
   post-GC floors, never from an in-flight `getUsed()`.
6. **`TIMED_WAITING (on object monitor)`** in the state histogram: `Object.wait()` with a timeout,
   i.e. an idle `Reference Queue`, not a lock wait.
7. `threads.dump` and `threads-2.dump` are not byte-identical (timestamp, `elapsed=`, `cpu=` differ)
   but their thread *sets* are identical; a multi-dump rule must diff semantics, not bytes.

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
