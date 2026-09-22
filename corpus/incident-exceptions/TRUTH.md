# corpus/incident-exceptions — ground truth

Real tool output from one JVM. Nothing hand-edited.

## How it was produced

```bash
java -Xms256m -Xmx256m -XX:+UseG1GC \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     -jar demo-victim.jar --server.port=8183 > app.log 2>&1 &
curl -s http://localhost:8183/victim/health
curl -s "http://localhost:8183/victim/errors?count=30"   # answers HTTP 500 on purpose
curl -s "http://localhost:8183/victim/errors?count=24"   # answers HTTP 500 on purpose
scripts/capture.sh -o corpus/incident-exceptions -p <pid> -d 3 --gc-log work/gc.log --app-log work/app.log
```

Automated form: `scripts/generate-corpus.sh incident-exceptions`.

## What was planted

`FlakyOrderService.burst(count)` loops through three recurring, *identically-shaped* failing call
paths (12 ms apart so the timestamps look real), then lets a fourth failure escape the handler:

| # | logged exception | `Caused by:` | deepest app frame | occurrences |
|---|---|---|---|---|
| 1 | `dev.jingyu.jia.victim.PaymentGatewayException` | `java.net.SocketTimeoutException: Read timed out` | `FlakyOrderService.callGateway(FlakyOrderService.java:87)` | **40** |
| 2 | `java.lang.IllegalArgumentException` (webhook payload) | `com.fasterxml.jackson.core.io.JsonEOFException` | `FlakyOrderService.parseWebhookPayload(FlakyOrderService.java:119)` | **27** |
| 3 | `java.lang.IllegalStateException` (inventory) | `java.util.concurrent.TimeoutException` | `FlakyOrderService.reserveStock(FlakyOrderService.java:103)` | **14** |
| 4 | escapes the handler: `IllegalStateException: refund REF-n failed…` | root cause `java.net.SocketTimeoutException` printed by Tomcat | `FlakyOrderService.handleRefund` | **2** (one per burst) |

All five frames of every path are thrown from a fixed source line, so the stacks are byte-identical
apart from the message text — that is what a fingerprinting rule must cluster on.

## Evidence actually present in these files

| artifact | evidence | observed |
|---|---|---|
| `app.log` | ` ERROR ` lines | **83** (81 from `d.jingyu.jia.victim.FlakyOrderService`, 2 from `o.a.c.c.C.[.[.[/].[dispatcherServlet]`) |
| `app.log` | `Caused by:` chains | **81**, of 3 distinct cause types: 40× `java.net.SocketTimeoutException`, 27× `com.fasterxml.jackson.core.io.JsonEOFException`, 14× `java.util.concurrent.TimeoutException` |
| `app.log` | logged exception headers | 40 `PaymentGatewayException`, 27 `IllegalArgumentException`, 14 `IllegalStateException`, 2 `SocketTimeoutException` (the container-printed root causes) |
| `app.log` | container entries | 2 × `Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: java.lang.IllegalStateException: refund REF-30 failed while talking to the gateway] with root cause` |
| `app.log` | `	at ` frame lines | 4900, of which **3320** are framework noise (`org.apache.catalina.core.ApplicationFilterChain.doFilter` 415×, `…internalDoFilter` 332×, `org.springframework.web.filter.OncePerRequestFilter.doFilter` 249×, `jdk.internal.reflect.NativeMethodAccessorImpl.invoke0`, `Method.java:568`, …) |
| `app.log` | jar / module markers on frames | `~[!/:1.0.0]` (fat-jar app classes), `~[spring-web-6.2.19.jar!/:6.2.19]`, `~[tomcat-embed-core-10.1.55.jar!/:na]`, `~[na:na]` (JDK) |
| `app.log` | line format | `2026-09-20 21:44:03.530 ERROR 5904 --- [nio-8183-exec-2] d.jingyu.jia.victim.FlakyOrderService    : cannot reserve inventory for SKU-701` then the throwable on its own lines |
| `app.log` | size | 663 KB / 5440 lines from 54 failures |
| `threads.dump` | 0 BLOCKED, 0 `victim-worker-`, no deadlock | clean |
| `gc.log` | 0 Full GC, 4 young pauses, max pause 8.5 ms | boring |
| `heap.histo` | `[B` = 3 MB, live total ≈ 13 MB | boring |

## Rules that SHOULD fire

- **EXC001 (stack-fingerprint clusters)** — 3 clusters above any sane "repeated" threshold
  (40 / 27 / 14), each with an identical frame list, plus 2 singletons.
- **EXC002 (causal chain)** — all 81 app-logged throwables carry exactly one `Caused by:`; the 3
  chain roots differ from the 3 wrapper types, so the rule can prove it reads the chain and not
  just the header. Verbatim: `40 wrapped failure(s) all bottom out at java.net.SocketTimeoutException
  thrown from at dev.jingyu.jia.victim.FlakyOrderService.readFromGateway(…)`, and 14 more at
  `checkStock(…)`.
- **EXC003 (burst in time)** — the same 40 do not merely repeat, they arrive inside one minute:
  `java.net.SocketTimeoutException arrives 40 times inside a single minute (total 40 in this log),
  starting 2026-09-20 21:44:00.000`, plus `TimeoutException … 14 times inside a single minute`. The
  rule exists because a cluster spread over an hour and a cluster that lands in one minute are
  different incidents, and only the second one has a timestamp worth paging somebody with.

## Rules that must NOT fire

- **TDA001/TDA002/TDA004** — the failures are logged, they never park on a monitor: **0 BLOCKED**
  threads in either dump, and no deadlock trailer.
- **TDA003/TDA005** — no `victim-worker-` family grows between the dumps and no pool is fully
  occupied; the request threads are busy failing, not stuck.
- **TDA006 (thread burning CPU)** — nothing reaches half a core between the two dumps.
- **GCA001-006** — 0 Full GC in this window; the exception traffic costs strings, not the
  collector. GCA006 additionally abstains because the flushed log covers 2.62 s and the rule needs
  10 s before it will quote a throughput percentage.
- **HIS001/HIS002** — 13 MB live total, the same baseline as `corpus/healthy`: the failures do not
  retain anything, they just allocate.
- **HIS003 (container count)** — the widest container row is `ConcurrentHashMap$Node` at 29,608
  against the `max(50,000, totalInstances/20)` = 50,000 floor (`Total 304012`).

## Honest caveats for rule tuning

- The throwable text starts at **column 0** on its own line, after the log line — Spring Boot's
  `%wEx` converter emits `java.lang.IllegalStateException: …` then `\tat …`. A parser that assumes
  every stack line is prefixed with the log timestamp will see 54 events instead of 5440 lines and
  lose the frames.
- Continuation lines are indented with a real TAB and the `Caused by:` line is prefixed by a TAB
  (`\tCaused by: …`) only for *nested* causes; the first line of the throwable is never indented.
- The container-logged entries put the exception class *inside* the message
  (`threw exception [Request processing failed: java.lang.IllegalStateException: …] with root
  cause`) and then print only the **root cause** stack — so `SocketTimeoutException` appears as a
  column-0 header without a `Caused by:` even though the app-level wrapper had one. Do not count
  these as a 4th cluster unless you want framework echo to inflate counts.
- Two bursts of 30 and 24 requests produce 40+27+14 (not 54+54) because `i % 4` distributes paths:
  the payment path runs on 3 of 4 iterations, so its cluster is the "repeated stack".
- Message text varies per iteration (`ORD-10002`, `SKU-701`, amounts) while frames stay identical —
  fingerprint on frames, not on the message, or the cluster collapses to 54 singletons.

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
