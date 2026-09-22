# Capture tooling and corpus reproduction

Everything here needs only a JDK on `PATH`/`JAVA_HOME` (`jps`, `jstack`, `jmap`, `jcmd`) and
`curl`. **Docker is not required** — the corpus in `../corpus/` was generated on a Windows host
with Git Bash, and `scripts/generate-corpus.sh` reproduces it end to end.

| file | what it is |
|---|---|
| `capture.sh` | grabs `threads.dump`, `threads-2.dump`, `heap.histo`, `gc.log`, `app.log` from a live JVM (Git Bash / Linux / WSL) |
| `capture.ps1` | the same thing for PowerShell, byte-compatible output (UTF-8 no BOM, LF endings) |
| `generate-corpus.sh` | starts one fresh JVM per incident, drives it, captures into `../corpus/<mode>/`, kills it |

## The JVM flags are part of the corpus

```
-Xms256m -Xmx256m -XX:+UseG1GC
-Xlog:gc*:file=gc.log:time,uptime,level,tags
-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
```

* 256 MB is what makes GCA001/GCA003/GCA004 reachable on a laptop — the heap leak and the GC storm
  do not bite on a default 8 GB heap.
* One mode deliberately deviates: `incident-zgc-leak` runs `-Xms1g -Xmx1g -XX:+UseZGC`. ZGC on
  JDK 17 wants that much room before its behaviour is interesting, and the reason the folder exists
  is that its GC log contains no `Pause Full` line at all — every G1-shaped assumption in the GC
  rules had to be re-checked against it.
* `-Xlog:gc*` is the JDK 9+ unified format; there is **no** `-XX:+PrintGCDetails` here, and no JDK 8
  output exists anywhere in this corpus.
* `file=` accepts a relative or an absolute path; JDK 17 tolerates the drive-letter colon in a
  Windows path. What it will **not** tolerate is a missing directory: the JVM refuses to start at
  all (`Error opening log file '…': No such file or directory` / `Invalid -Xlog option`), so
  `generate-corpus.sh` starts each JVM with `cd` into a directory it just created and writes
  `gc.log` there. `capture.sh` resolves that relative name against the target's own `user.dir`.
* UTF-8 matters because the host default encoding here is GBK.

## Reproducing every incident and its capture

Build once first:

```bash
sh "/d/daily-files/Qoder CN/project-3/.tools/apache-maven-3.9.9/bin/mvn" -B -q \
   -Dmaven.repo.local="/d/daily-files/Qoder CN/project-3/.tools/m2repo" \
   -f ../demo-victim/pom.xml package
```

Then, per incident, three commands: start, trigger, capture. All seven in one line at the end.

### 1. deadlock → `corpus/incident-deadlock`

```bash
mkdir -p w/deadlock && cd w/deadlock
java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -jar ../../demo-victim/target/demo-victim.jar --server.port=8080 > app.log 2>&1 &
curl -s localhost:8080/victim/deadlock; sleep 6
../../scripts/capture.sh -o ../../corpus/incident-deadlock -d 6
```
Check: `grep 'Found one Java-level deadlock' corpus/incident-deadlock/threads.dump` (and 10 BLOCKED).

### 2. heap leak → `corpus/incident-heap-leak`

```bash
for i in $(seq 1 8); do curl -s "localhost:8080/victim/leak?mb=24&rows=50000" >/dev/null; sleep 2; done
../../scripts/capture.sh -o ../../corpus/incident-heap-leak -d 6
curl -s -X DELETE localhost:8080/victim/leak          # undo; the JVM recovers
```
Check: `grep -c 'Pause Full' …/gc.log` ≥ 8, post-GC floor `255M->255M(256M)`, and
`head -5 …/heap.histo` showing `[B` around 173 MB.

### 3. GC storm → `corpus/incident-gc-storm`

```bash
curl -s "localhost:8080/victim/gcstorm?rounds=2000&workingSetMb=175"   # async on victim-batch-1
sleep 4
../../scripts/capture.sh -o ../../corpus/incident-gc-storm -d 6 \
     --between-cmd 'curl -s "localhost:8080/victim/healthy?iterations=4000&payload=32768"'
```
Check: `Pause Full (G1 Compaction Pause) 236M->236M(256M)` ×8 among ~1900 young pauses.
Pass `blocking=true` to make the call wait for the batch instead.

### 4. thread leak → `corpus/incident-thread-leak`

```bash
curl -s "localhost:8080/victim/leak-threads?count=80"; sleep 3
../../scripts/capture.sh -o ../../corpus/incident-thread-leak -d 8 \
     --between-cmd 'curl -s "localhost:8080/victim/leak-threads?count=60"'
```
Check: 80 `"victim-worker-` in `threads.dump`, 140 in `threads-2.dump` — the growth across the two
dumps is the point.

### 5. exceptions → `corpus/incident-exceptions`

```bash
curl -s "localhost:8080/victim/errors?count=30"   # HTTP 500 is intentional
curl -s "localhost:8080/victim/errors?count=24"
../../scripts/capture.sh -o ../../corpus/incident-exceptions -d 3
```
Check: 83 `ERROR` lines, 81 `Caused by:`, clusters of 40/27/14 identical stacks.

### 6. healthy baseline → `corpus/healthy`

```bash
for i in 1 2 3 4; do curl -s "localhost:8080/victim/healthy?iterations=2500&payload=16384" >/dev/null; sleep 2; done
../../scripts/capture.sh -o ../../corpus/healthy -d 6
```
Check: 0 BLOCKED, 0 `victim-worker-`, 0 `Pause Full`, max pause 8.4 ms, 12.3 MB live in the
histogram. `corpus/healthy/TRUTH.md` is the zero-findings contract for the analyzer.

### 7. the same leak under ZGC → `corpus/incident-zgc-leak`

```bash
mkdir -p w/zgc && cd w/zgc
java -Xms1g -Xmx1g -XX:+UseZGC -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -Dfile.encoding=UTF-8 -jar ../../demo-victim/target/demo-victim.jar --server.port=8080 > app.log 2>&1 &
for i in $(seq 1 14); do curl -s "localhost:8080/victim/leak?mb=96" >/dev/null; sleep 2; done
../../scripts/capture.sh -o ../../corpus/incident-zgc-leak -d 6
```
Check: `grep -c 'Allocation Stall (' gc.log` ≥ 1, `grep -c 'Pause Full' gc.log` == 0 (that zero is
the entire point of the folder), occupancy lines like `1014M(99%)->1012M(99%)`, and
`jia analyze corpus/incident-zgc-leak` ranking the leak first. ZGC on JDK 17 wants 1 GB before it
is interesting; 256 MB is what makes the G1 modes bite.

### All seven, from a clean checkout

```bash
bash scripts/generate-corpus.sh                 # builds demo-victim.jar first, then 7 captures (~5 min)
bash scripts/generate-corpus.sh --skip-build healthy incident-deadlock
CORPUS_DIR=/tmp/corpus bash scripts/generate-corpus.sh --skip-build incident-zgc-leak
```
`CORPUS_DIR` exists because the default output directory is `corpus/` — a run **replaces tracked
artifacts**, and one mode also needs ~1 GB of free heap. Reproduce into scratch, compare the
conclusions, and only write into `corpus/` when you mean to re-record the folder (its `TRUTH.md`
quotes the committed capture's own numbers).

## capture.sh / capture.ps1 options

```
-o|--out DIR      where to write (default .)
-p|--pid PID      target pid (default: first jps -l match)
-m|--match STR    jps -l filter, default "demo-victim"
-d|--delay SECS   gap between the two thread dumps, default 5
--between-cmd CMD shell command run between the dumps (drive the incident while you capture)
--gc-log FILE     the JVM's -Xlog file (default: auto-detected from jcmd VM.command_line)
--app-log FILE    the app's stdout file (default: ./app.log, ./logs/app.log)
--skip-histo      do not run jmap (keeps the JVM completely undisturbed)
```

Ordering that matters: dump 1 → delay → (`--between-cmd`) → dump 2 → **copy `gc.log`/`app.log`** →
`jmap -histo:live`. The copies happen before the histogram because `jmap -histo:live` forces a full
collection of its own, and that entry is tooling, not incident. Pass `--skip-histo` if the target
must not be touched at all.

## PowerShell

```powershell
& .\scripts\capture.ps1 -Out .\corpus\incident-deadlock -Delay 6
& .\scripts\capture.ps1 -Out .\corpus\incident-thread-leak -Delay 8 `
    -BetweenCmd 'curl.exe -s "http://localhost:8080/victim/leak-threads?count=60"'
```
`capture.ps1` writes UTF-8 without BOM and LF line endings so its files are byte-comparable with
`capture.sh` output. `generate-corpus.sh` is bash-only (Git Bash is fine); on PowerShell drive the
incidents by hand with the seven blocks above.

## Troubleshooting

* **`gc.log` is shorter than the JVM's uptime.** Expected: `-Xlog:...:file=` output is
  block-buffered, so copying it from a *running* JVM yields only what the JVM had flushed (the
  quiet captures here contain the first 2.6-8.1 s of a 13-26 s session; the two pressure captures,
  84 KB and 4.7 MB, contain the whole incident because the buffer kept overflowing). The rest
  reaches disk when the buffer fills or on a clean JVM exit - a forced kill never flushes. Drive a
  real incident before you capture, and if you need the complete file, restart the JVM with a new
  `file=` path and capture after `shutdown`.

* `no JVM matching 'demo-victim'` — start the app first, or pass `-p <pid>`; the pid is also the
  second field of every log line and `GET /victim/health` returns it as `"pid"`.
* `jstack` failing with "Unable to open socket file" on Linux means the capture process is not in
  the target's PID namespace / does not share its `/tmp` — see `server/docker-compose.yml`, where
  `pid: "host"` plus a shared `jvm-tmp` volume exist precisely for that.
* `capture.sh: jmap failed` under a GC storm is normal-ish: the JVM must reach a safepoint. Retry
  once the load eases, or use `--skip-histo` and take the histogram later.
