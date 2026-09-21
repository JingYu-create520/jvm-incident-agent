# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the version follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
