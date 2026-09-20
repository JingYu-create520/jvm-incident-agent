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

- **Exit code is load-bearing.** `0` nothing high-severity, `1` at least one HIGH/CRITICAL
  finding, `2` the input was not understood.
- The first hypothesis is the answer to give. Lower-ranked ones are correlated observations,
  not independent incidents.
- Every finding carries `file:line` evidence. If a claim matters, open that line and check it
  before repeating it — that is what the evidence column is for.
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
| `corpus/healthy` | a working service — must produce **zero** findings |
