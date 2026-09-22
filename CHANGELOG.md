# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the version follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 0.2.0 — 2026-09-22

Found by capturing something the corpus did not contain: a heap leak under ZGC. Until now every GC
log in `corpus/` came from a G1 JVM, so nothing in the build ever asked what "major collection"
means to a collector that has no Full GC. It turned out that three behaviours were written against
G1's vocabulary while being presented as general ones, and two of the three produced **confident
false conclusions** rather than silence.

### Fixed

- **A ZGC JVM was reported as a starved thread pool.** jstack quotes ZGC's workers like threads
  (`"ZWorker#0" … runnable`, `"RuntimeWorker#3" … runnable`) with no `java.lang.Thread.State:` line
  and no frames; TDA005 grouped them into a name family, found them all "occupied", and printed
  `All 4 workers of "ZWorker" are occupied and 4 of them sit in the same frame (<empty stack>)` —
  as the top hypothesis. `JThread.isVmWorker()` (no frames, no monitors, no ownable synchronizers)
  now keeps them out of every name-family grouping, which also covers TDA003 and, through
  `ThreadNoise.jvmInternal`, TDA004 and TDA006.
- **Stop-the-world time included work that was never a stop.** A `[gc,stats]` table prints rolling
  averages per collection (`Collector: Garbage Collection Cycle  30.142 / 613.231 … ms`), and the
  record merge took the last millisecond figure it saw. On the ZGC capture that turned a real
  stop-the-world total of ~10 ms out of 48.8 s into a reported **63.4 % of wall time in pauses**,
  with GCA002 firing off the same line. Statistics rows are now skipped by shape, a record's
  summary duration is separated from its phase durations, and phase durations are summed rather
  than sampled.
- **The same inflation existed in the G1 corpus, and the published numbers move.**
  `corpus/incident-gc-storm`'s GCA006 was `48.6% of wall time … (10694 ms across 2555 pauses)`;
  it is now `7.9% … (1737 ms across 2548 pauses)`, which drops that finding from CRITICAL to
  MEDIUM. The removed 8.9 s was 660 concurrent-mark cycles counted as application downtime. The
  storm itself is unchanged — GCA001, GCA004, GCA005 and HIS001 read what they always read, and
  `H-ALLOCATION-STORM` is still the top hypothesis — but anything this project has said about the
  cost of that storm was overstated, including the article in `docs/`.
- `corpus/incident-gc-storm` and `corpus/incident-thread-leak` each listed a fired rule under
  "must NOT fire" (GCA005, justified by `GCLocker Initiated GC` in the log; TDA005, which fires
  because all 80 leaked workers are occupied in one frame); `incident-heap-leak` claimed HIS002
  fires when it misses its 10 % share floor at 9.32 %, and omitted GCA004; `incident-exceptions`
  omitted EXC003; `corpus/healthy` still said "14 rules" and accounted for 14 of 18.
- Both READMEs' no-Docker capture snippet started the victim without `-Xlog:gc*`, so the fourth
  artifact it claims to collect could not exist. It now passes the flag and the `--gc-log` /
  `--app-log` paths, verified end to end against a fresh 256 MB G1 storm.

### Added

- **ZGC is a collector this tool reads, not a format it abstains on.** `GcLog.isMajor(kind)` maps
  a whole-heap concurrent cycle to "major" for ZGC and Shenandoah, so GCA001 and GCA003 have
  something to look at: the new `corpus/incident-zgc-leak` capture is reported as
  `Across 278 major collections the heap low-water mark climbed from 20M to 1012M (+4960%) …
  (capacity 1024M)` and ranks the leak first — the same planted bug as `incident-heap-leak`, on a
  collector with no Full GC. ZGC's occupancy is read from its percentage form
  (`1014M(99%)->1012M(99%)`) with `Max Capacity:` as the denominator.
- **GCA007 — allocation stalls.** `Allocation Stall (http-nio-18081-exec-8) 31.866ms` is what a
  ZGC heap that cannot keep up actually prints, and the thread named in the line is the useful
  part: it says whether latency or throughput is being paid. Fires at three stalls or one over the
  pause SLA. 19 rules now.
- `--fail-on never|info|low|medium|high|critical` (default `high`, the historical behaviour), so a
  CI gate can require CRITICAL instead of being broken by an advisory MEDIUM. A bad value is exit 2.
- `TruthDocTest` asserts each `corpus/*/TRUTH.md` against the engine: its SHOULD-fire list must
  equal the rules that actually fire, its must-NOT-fire list must be disjoint from them, and the
  two together must account for all 19 rules. Both markdown shapes the corpus grew up with are
  parsed (id-prefixed bullets, and the healthy folder's rule-by-rule table), including ranges like
  `GCA001-006`. Mutation-checked: moving a fired rule into the must-NOT list, deleting a row, and
  naming a rule that was never registered each fail the build.
- The collector is now identified from the pause vocabulary when the log has no `Using …` banner,
  which is the normal shape of a rotated `gc.log.0`.
- `corpus/incident-zgc-leak`: real ZGC output from this JDK (17.0.5, `-XX:+UseZGC -Xmx1g`), with a
  `TRUTH.md` that records the three defects above as the reason the folder exists.
- A ZGC parser fixture cut from that capture's own log, byte for byte, deliberately from the middle
  of the file so it carries no banner either.

### Changed

- `jia analyze --format json` renamed the `fullGcs` input summary to `majorCollections`, because
  under ZGC it counts cycles. `GCA001`'s `fullGcs` metric followed the same name.

## 0.1.2 — 2026-09-21

Found by re-running the Docker path on a machine that already had six containers published
(8080, 8081, 3000, 5432, 6379, 9090 all taken) — which is the situation 0.1.1's port fix was
supposed to survive.

### Fixed

- `server/docker-compose.yml` no longer publishes **any** host port. 0.1.1 moved the victim off
  a hardcoded 8080 and made it `VICTIM_PORT` (default 8081), but that only relocated the same
  failure: with something else on 8081, `docker compose --profile repro run --build --rm incident
  deadlock` still died with `Bind for 0.0.0.0:8081 failed: port is already allocated`. The
  one-click flow reaches the container over the compose network, so the mapping was never needed;
  README (both languages) now says so and shows how to publish a port deliberately if you want a
  browser URL.
- `corpus/incident-deadlock/TRUTH.md` listed TDA005 under "rules that must NOT fire". It fires,
  and it is right to: the planted deadlock occupies all 8 workers of `victim-report-fetcher`, so
  the pool really is starved. Moved to the SHOULD-fire list with the reason.

### Added

- `CorpusTest` now pins the **exact** rule set each scenario fires. The ranking check only looked
  at the top hypothesis, so a rule drifting in or out of a scenario stayed invisible — that is how
  the TDA005 line above survived contact with the corpus it describes.

### Verified

- `docker compose --profile repro run --build --rm incident deadlock` end to end on Docker Desktop
  29.8.0 / Windows: image builds, deadlock plants, four artifacts land in `corpus/incident-deadlock`,
  and `jia analyze` on the fresh capture reports TDA001 (99%), TDA005, TDA004, TDA002 — with the
  whole suite (80 tests) green against the newly captured files.

## 0.1.1 — 2026-09-21


Found by actually running the Docker path end to end, which 0.1.0 shipped unverified because no
daemon was available during the build. Everything below is a defect in the reproduction
tooling or a rough edge in the container story, not in the analysis rules.

### Fixed

- `scripts/capture.sh` died under `sh` with `set: Illegal option -o pipefail`. It is a bash
  script, but `server/run-incident.sh` and the compose `capture` entrypoint both launched it as
  `sh …`, which is what a container entrypoint naturally does. The script now re-execs under
  bash when started by a POSIX shell, and both call sites ask for bash directly.
- `server/docker-compose.yml` published the victim on host port 8080 unconditionally, so the
  one-click command failed with `port is already allocated` on any machine running something
  else there. It is now `VICTIM_PORT` (default 8081) — and the one-click flow never needed the
  mapping anyway, since `run-incident.sh` reaches the container over the compose network.

### Added

- `jia analyze` accepts no path and analyzes the working directory. Combined with the image's
  `WORKDIR /input` and `CMD ["analyze"]`, the whole container story collapses to
  `docker run --rm -v "$PWD/incident:/input" jia`.
- Verified end to end on this machine: `docker compose --profile repro run --build --rm incident
  deadlock` builds the victim image, plants a real deadlock, captures four artifacts, and
  `jia analyze` on the fresh folder ranks `H-DEADLOCK` first at 99% confidence. The analyzer
  image itself now builds and reports `H-DEADLOCK` on the incident mount and "no high-confidence
  problem found" on the healthy one.

### Documented

- README (both languages) now shows the commands that were actually run, including the Windows
  Git Bash trap where `-v "$PWD/incident:/input:ro"` silently mounts nothing because `$PWD` is an
  MSYS path (`/d/…`); use a `D:/…` host path there, or PowerShell.

## 0.1.0 — 2026-09-20

First release. The scope is one sentence: put JVM incident artifacts in, get an
evidence-backed root-cause report out — offline, deterministic, agent-callable.

### Added

- **Parsing layer** (`dev.jingyu.jia.parse`)
  - `ThreadDumpParser` — an explicit state machine over `jstack -l` and `jcmd Thread.print`,
    covering the JDK 8 → 21 drift: the optional `cpu=`/`elapsed=` columns, module-qualified
    frame prefixes (`java.base@17.0.5/…` and the same form inside the parentheses),
    `Locked ownable synchronizers` sections, and the deadlock trailer that repeats thread
    stanzas (which is collected, never re-counted as threads).
  - `GcLogParser` — both families: JDK 8 traditional `-XX:+PrintGCDetails` (multi-line blocks
    closed by `[Times: …]`) and JDK 9+ unified `-Xlog:gc*` (bracketed decorators, `GC(n)`
    records merged back into one collection). Heap, old-generation and metaspace figures are
    normalised to bytes; the JVM start time is derived when a log carries both clocks.
  - `HistoParser` — `jmap -histo[:live]` tables, module suffix stripped, totals recovered
    from rows when the `Total` line is absent.
  - `StackParser` — throwable blocks from arbitrary application logs, including logback's
    `~[jar:version]` frame suffix and the timestamp carried on the *preceding* log line;
    `Caused by:` chains kept as chains.
  - `Sniffer` — artifact type decided by content signatures, filenames only as a tiebreaker.
  - Charset detection (UTF-8 → GBK → Latin-1) and a 128 MB per-file guard; nothing the loader
    cannot read aborts a run, it becomes an `unparsed` entry with a reason.
- **Rule engine** — 18 deterministic rules, each one class, each with its own documentation
  including an explicit false-positive section:
  - thread dumps: `TDA001` deadlock (wait-for graph + iterative Tarjan SCC, monitors *and*
    `java.util.concurrent` locks), `TDA002` contended monitor, `TDA003` thread leak,
    `TDA004` stack hotspot (plus RUNNABLE-on-socket), `TDA005` pool starvation,
    `TDA006` CPU-burning thread
  - GC log: `GCA001` Full GC storm in the densest window, `GCA002` pause over SLA
    (p50/p95/p99/max), `GCA003` rising live set and heap-full plateau, `GCA004` premature
    promotion, `GCA005` configuration smells, `GCA006` throughput
  - heap histogram: `HIS001` dominant class, `HIS002` application class worth a heap dump,
    `HIS003` collection-count anomaly
  - application log: `EXC001` exception clusters, `EXC002` causal-chain attribution,
    `EXC003` time burst
- **Hypothesis ranking** — findings are correlated into root-cause hypotheses with explicit
  specificity weights, so a symptom (GC storm) never outranks its own explanation (growing live
  set). `WaitForGraph`, `TarjanSCC` (iterative — a dump can hold tens of thousands of threads).
- **Reports** — `MarkdownReport` (verdict, findings table, ranked hypotheses, timeline, per-finding
  evidence block, and a "Coverage and limits" section that states what had nothing to read) and
  `JsonReport` (schema `jvm-incident-agent/1`).
- **LLM layer, narration only** — `Provider`, offline `MockProvider` (default),
  `OpenAICompatProvider` for any OpenAI-compatible endpoint via
  `JIA_LLM_BASE_URL` / `JIA_LLM_API_KEY` / `JIA_LLM_MODEL`. Findings are complete before any model
  is contacted, and `EngineTest.narrativeCannotMoveFindings` fails if a provider ever changes them.
  Only finding summaries are sent — never raw artifacts.
- **MCP server** — `jia mcp` over stdio, newline-framed JSON-RPC 2.0 with
  `analyze_snapshot` (path *or* pasted content), `explain_finding`, `list_rules`. Hand-written
  rather than SDK-based; the trade-off is recorded in `docs/adr/0001-hand-written-mcp-transport.md`.
- **CLI** — `analyze`, `rules`, `explain`, `doctor`, `mcp` on Picocli, with tunable thresholds
  (`--sla-ms`, `--full-gc-per-min`, `--thread-leak-threshold`, `--lock-waiters`,
  `--stack-cluster`, `--exception-threshold`, `--histo-share`, `--throughput`) and exit codes that
  are safe to assert on: `0` nothing high-severity, `1` HIGH/CRITICAL present, `2` input not understood.
- **Target application and real corpus** — `demo-victim` (Spring Boot 3) with five planted incidents
  and a deliberately clean path; `scripts/capture.sh` / `.ps1` and `scripts/generate-corpus.sh`;
  `server/docker-compose.yml` for one-click reproduction. `corpus/` holds six scenarios of byte-for-byte
  real `jstack`/`jmap`/GC-log/application-log output, each with a `TRUTH.md`.
- **Tests** — 74: parser fidelity (including malformed input that must not crash), every rule with a
  positive and a negative case, the healthy-capture zero-finding gate, the LLM lock, duplicate merging,
  exit codes, MCP wire protocol, and `CorpusTest` asserting that each scenario's top hypothesis names
  the planted truth.
- **Docs** — bilingual README, machine-generated rule catalogue (`docs/rules.md`),
  the original design document (`docs/PLAN.md`), and
  `docs/article-anatomy-of-a-full-gc-storm.zh-CN.md` walking through a real capture.

### Deliberately not in 0.1.0

HPROF heap-dump parsing (the report tells you what to look for in MAT instead), async-profiler
flame graphs, JFR analysis, an in-process agent, and a web UI — the report and the MCP tools are
the interface.
