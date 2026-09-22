---
name: jvm-incident-agent
description: Analyze JVM incident artifacts — jstack thread dumps, GC logs, jmap -histo output and application-log exception stacks — and produce a root-cause report where every finding quotes file:line evidence. Use when a user shares a thread dump, a GC log, an OutOfMemoryError, a deadlock, "Full GC 风暴", long pauses, a heap that keeps growing, leaked threads, BLOCKED threads, or asks "为什么这个服务卡了/挂了". Works offline; nothing is uploaded.
---

# JVM incident analysis

`jia` parses four kinds of incident artifact and returns findings that each quote the exact
line they came from. It never guesses at a root cause: fixed rules produce the facts, and the
language model only turns them into prose.

## When to reach for this

- A thread dump, GC log, `jmap -histo` output or a log full of stack traces appears in the
  conversation, pasted or as a file.
- Symptoms named as: deadlock, threads stuck in BLOCKED, service hanging, Full GC storm,
  pause too long, heap growing continuously, `OutOfMemoryError`, `unable to create new native
  thread`, CPU pinned by one thread, a repeating exception.

Do not use it for `.hprof` heap dumps (not supported), profiling flame graphs, or JFR.

## How to call it

Prefer the MCP tools when they are registered; otherwise run the CLI jar.

```bash
jia analyze <directory-or-file> [--format md|json|both] [-o out/]
jia explain TDA001          # what a rule detects, how, and its false-positive limits
jia rules                   # the catalogue
jia doctor                  # what this JVM/environment can do
jia mcp                     # stdio MCP server: analyze_snapshot, explain_finding, list_rules
```

Point it at a **directory** whenever several artifacts exist together — cross-signal
correlation (GC log + histogram + two dumps) is where the useful conclusions come from. Two
dumps taken seconds apart unlock the growth-based rules that a single dump cannot answer.

`analyze_snapshot` also accepts `content` with `fileName`, so a dump pasted into chat can be
analyzed without writing it to disk first.

## Reading the result

- **Exit code is load-bearing.** `0` nothing at or above the gate, `1` something is, `2` the input
  was not understood. The gate is `--fail-on` (`never|info|low|medium|high|critical`, default
  `high`): in CI prefer `--fail-on critical` so an advisory `MEDIUM` cannot break a build, and
  `--fail-on never` when the report is what you are after.
- **"No Full GC" is not "no GC problem".** Under ZGC the collection that reveals the live set is the
  whole-heap concurrent cycle, and the pressure signal is `Allocation Stall (<thread>) 31.866ms`
  (GCA007). If the log has no `Pause Full`, read GCA001/GCA003/GCA007 before concluding the
  collector is fine — and remember ZGC's own `"ZWorker#N"` threads are not a thread pool.
- The first hypothesis is the answer to give. Lower-ranked ones are correlated observations,
  not independent incidents.
- Every finding carries `file:line` evidence. If a claim matters, open that line and check it
  before repeating it — that is what the evidence column is for.
- **Check `ignoredInputs` (`report.json`, or the bullets under "Coverage and limits") before you say
  a window was covered.** A snapshot reads one GC log and one histogram; if the folder had a rotated
  set or two `jmap` outputs, the extras were dropped and named. "No leak in this incident" means
  something different when the findings describe 40 minutes of a 3-hour log.
- **"No exception burst" can mean the log had no clock.** EXC003 counts only stacks whose timestamp
  resolves to an absolute instant, and a `HH:mm:ss.SSS` console pattern does not. `exceptionClock`
  (JSON) and the coverage bullet both report how many of the parsed stacks were datable — quote those
  numbers instead of concluding the errors were spread out.
- **A rule that abstained is not a rule that found nothing.** `report.json`'s `ruleStatus` says
  `declined: <reason>` for the rules that had the artifact but too little of it — a Full GC rate needs
  two major collections, a live-set fingerprint needs `--heap-leak-min-full-gc`, a throughput percentage
  needs ten events over ten seconds, thread *growth* needs a second dump. The same list is in the
  Markdown's coverage section. Never summarise a declined rule as "no problem found"; ask for the missing
  capture instead.
- Rule IDs are stable. `jia explain <ID>` gives the mechanism and the known false positives;
  quote it rather than re-deriving why a rule fired.

## What to avoid

- Do not invent findings the rules did not produce, and do not reweight severities. If the
  tool says nothing, say nothing was detected and name what is missing (e.g. "no GC log in
  this snapshot").
- Do not present `INFO` entries as incidents — they exist to admit degraded input.
- Do not suggest `-XX:+DisableExplicitGC`, heap resizing, or pool-size changes before the
  underlying finding (a leak, a lock, a downstream call) has been dealt with — those are the
  recommendations the rules already rank.
- Do not upload artifacts anywhere. If a user's dumps are sensitive, this tool is the point:
  analysis happens on their machine.

## Reproducing an incident on purpose

`demo-victim/` is a Spring Boot app with planted incidents and `corpus/` holds real captures of
each one (`scripts/README.md` shows how to regenerate). Useful when a claim needs demonstrating
rather than asserting:

| directory | planted truth |
|---|---|
| `corpus/incident-deadlock` | two threads, two monitors, opposite order |
| `corpus/incident-heap-leak` | unbounded `byte[]` cache, live set pinned at the ceiling |
| `corpus/incident-gc-storm` | thousands of short-lived large arrays |
| `corpus/incident-thread-leak` | threads created per request, never closed |
| `corpus/incident-exceptions` | wrapped `SocketTimeoutException` clusters in one burst |
| `corpus/incident-zgc-leak` | the same unbounded cache, on ZGC — no `Pause Full` exists in the log |
| `corpus/healthy` | a working service — must produce **zero** findings |
