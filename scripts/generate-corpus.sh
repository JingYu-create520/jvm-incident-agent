#!/usr/bin/env bash
# generate-corpus.sh - rebuild the whole corpus/ directory from scratch on the host, no Docker.
#
# For every incident it starts a FRESH demo-victim JVM with the documented flags, drives one
# endpoint until the incident is really happening, runs scripts/capture.sh against that pid, then
# kills the JVM. That way each corpus/<mode>/ folder contains exactly one incident and nothing
# else, and every byte in it is real jstack / jmap / -Xlog / application output.
#
# Usage:
#   scripts/generate-corpus.sh                  # all six captures
#   scripts/generate-corpus.sh healthy          # only one (or several) modes
#   scripts/generate-corpus.sh --skip-build incident-deadlock incident-gc-storm
#
# Modes: incident-deadlock incident-heap-leak incident-gc-storm incident-thread-leak
#        incident-exceptions healthy
#
# Scratch (the JVM's own gc.log/app.log before they are copied into corpus/) lives under
# demo-victim/target/corpus-work/, i.e. inside Maven's target/ directory, and is deleted on exit.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$ROOT/demo-victim/target/demo-victim.jar"
WORK="$ROOT/demo-victim/target/corpus-work"
CORPUS="$ROOT/corpus"
CAPTURE="$SCRIPT_DIR/capture.sh"
MVN="${MVN:-}"
IS_WINDOWS=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;; esac
SH="bash"; command -v bash >/dev/null 2>&1 || SH=sh

SKIP_BUILD=0
MODES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    -h|--help)    sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            MODES+=("$1"); shift ;;
  esac
done
[ ${#MODES[@]} -gt 0 ] || MODES=(incident-deadlock incident-heap-leak incident-gc-storm \
                               incident-thread-leak incident-exceptions healthy)

# ---------------------------------------------------------------- toolchain
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-}/bin" ]; then
  echo "generate-corpus.sh: JAVA_HOME must point at a JDK (needs bin/java and bin/jps)" >&2
  exit 3
fi
JAVA="$JAVA_HOME/bin/java"; JPS="$JAVA_HOME/bin/jps"
if [ "$IS_WINDOWS" = 1 ]; then JAVA="$JAVA.exe"; JPS="$JPS.exe"; fi
[ -x "$JAVA" ] || { echo "generate-corpus.sh: cannot exec $JAVA" >&2; exit 3; }

# keep every downloaded artifact inside the project
M2REPO="$ROOT/../.tools/m2repo"
[ -d "$M2REPO" ] || M2REPO="$ROOT/demo-victim/target/m2repo"

echo "==> JAVA   = $JAVA"
echo "==> JAR    = $JAR"
echo "==> CORPUS = $CORPUS"

# ---------------------------------------------------------------- build
if [ "$SKIP_BUILD" = 0 ]; then
  if [ -z "$MVN" ]; then
    for c in "$ROOT/../.tools/apache-maven-3.9.9/bin/mvn" "$(command -v mvn || true)"; do
      [ -n "$c" ] && [ -f "$c" ] && { MVN="$c"; break; }
    done
  fi
  if [ -z "$MVN" ]; then
    echo "generate-corpus.sh: no mvn found - pass MVN=/path/to/mvn or use --skip-build" >&2
    exit 3
  fi
  echo "==> building demo-victim.jar (maven repo: $M2REPO)"
  ( cd "$ROOT/demo-victim" && sh "$MVN" -B -q "-Dmaven.repo.local=$M2REPO" package ) || exit 1
fi
[ -f "$JAR" ] || { echo "generate-corpus.sh: missing $JAR - build it first" >&2; exit 1; }

# ---------------------------------------------------------------- helpers
PORT_BASE=8181
JVM_PID=""; PORT=8181; MODE_NUM=0; WD=""; DELAY=6; BETWEEN=""

kill_jvm() {
  [ -n "$JVM_PID" ] || return 0
  if [ "$IS_WINDOWS" = 1 ]; then
    taskkill //PID "$JVM_PID" //T //F >/dev/null 2>&1 || true
  else
    kill -9 "$JVM_PID" >/dev/null 2>&1 || true
  fi
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    "$JPS" -l 2>/dev/null | grep -q "^$JVM_PID " || { echo "    jvm $JVM_PID stopped"; break; }
    sleep 1
  done
  JVM_PID=""
}
cleanup() { kill_jvm; [ -n "${KEEP_WORK:-}" ] || rm -rf "$WORK" 2>/dev/null || true; }
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

api() { curl -s -m "${API_TIMEOUT:-240}" "http://127.0.0.1:$PORT$1"; }

used_ratio() { # heap used / max as a float, from /victim/health
  api /victim/health | tr ',' '\n' | awk -F: '
    /"heapUsedMb"/{u=$2+0} /"heapMaxMb"/{m=$2+0}
    END{ if (m > 0) printf "%.4f", u / m; else print "-1" }'
}

pause_full_count() { local n; n="$(grep -c 'Pause Full' "$WD/gc.log" 2>/dev/null)"; echo "${n:-0}"; }

start_jvm() {
  MODE_NUM=$((MODE_NUM + 1))
  PORT=$((PORT_BASE + MODE_NUM))
  WD="$WORK/$1"
  rm -rf "$WD" && mkdir -p "$WD"
  echo
  echo "=== $1: starting a fresh JVM (port $PORT, scratch $WD)"
  # gc.log goes to a path relative to the JVM's own cwd. JDK 17 also accepts an absolute Windows
  # path here (the drive-letter colon survives -Xlog's ':' splitting), but the JVM refuses to
  # start at all if the directory does not exist, so cwd-relative is the safe form.
  ( cd "$WD" && "$JAVA" \
      -Xms256m -Xmx256m -XX:+UseG1GC \
      "-Xlog:gc*:file=gc.log:time,uptime,level,tags" \
      -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
      -jar "$JAR" --server.port="$PORT" > "$WD/app.log" 2>&1 & )
  JVM_PID=""
  for _ in $(seq 1 90); do
    grep -q 'Started VictimApplication' "$WD/app.log" 2>/dev/null || { sleep 1; continue; }
    JVM_PID="$(grep -o '[0-9]* ---' "$WD/app.log" 2>/dev/null | head -1 | awk '{print $1}')"
    [ -n "$JVM_PID" ] && break
    sleep 1
  done
  [ -n "$JVM_PID" ] || { echo "generate-corpus.sh: JVM did not come up" >&2; tail -20 "$WD/app.log" >&2; exit 1; }
  echo "    pid=$JVM_PID  ($("$JPS" -l 2>/dev/null | grep "^$JVM_PID" | cut -c1-80))"
}

finish() { # finish <mode> : capture the four artifacts of the running JVM into corpus/<mode>
  local dir="$CORPUS/$1" args
  mkdir -p "$dir"
  args=(-o "$dir" -p "$JVM_PID" -d "$DELAY" --gc-log "$WD/gc.log" --app-log "$WD/app.log")
  [ -n "$BETWEEN" ] && args+=(--between-cmd "$BETWEEN")
  echo "==> capturing into ${dir#$ROOT/}"
  "$SH" "$CAPTURE" "${args[@]}" || { echo "generate-corpus.sh: capture failed for $1" >&2; exit 1; }
  kill_jvm
  BETWEEN=""
}

# ---------------------------------------------------------------- the six captures
for MODE in "${MODES[@]}"; do
  DELAY=6; BETWEEN=""
  case "$MODE" in
  incident-deadlock)
    start_jvm "$MODE"
    for i in 1 2 3; do api "/victim/health" >/dev/null; api "/victim/healthy?iterations=200" >/dev/null; sleep 1; done
    echo "    planting the deadlock"; api "/victim/deadlock"; echo
    sleep 6                                   # let the cycle and the 8 hot-lock waiters settle
    finish "$MODE"
    ;;

  incident-heap-leak)
    start_jvm "$MODE"
    api "/victim/health" >/dev/null
    echo "    ramping the unbounded session cache"
    r=0
    for i in $(seq 1 10); do
      r="$(used_ratio)"
      awk -v r="$r" 'BEGIN{ exit (r >= 0.78) ? 0 : 1 }' && break
      api "/victim/leak?mb=24&rows=50000" >/dev/null
      sleep 2
    done
    echo "    used/max=$r full-GCs-so-far=$(pause_full_count) - pushing past the wall"
    for i in $(seq 1 12); do
      [ "$(pause_full_count)" -ge 8 ] && break
      api "/victim/leak?mb=8&rows=12000" >/dev/null
      sleep 2
    done
    echo "    used/max=$(used_ratio) full-GCs=$(pause_full_count)"
    finish "$MODE"
    ;;

  incident-gc-storm)
    start_jvm "$MODE"
    api "/victim/health" >/dev/null
    echo "    starting the humongous batch on victim-batch-1 (async: capture runs mid-storm)"
    api "/victim/gcstorm?rounds=2000&workingSetMb=175"; echo
    sleep 4
    BETWEEN="curl -s -m 120 \"http://127.0.0.1:$PORT/victim/healthy?iterations=4000&payload=32768\" >/dev/null"
    echo "    used/max=$(used_ratio) full-GCs=$(pause_full_count)"
    finish "$MODE"
    ;;

  incident-thread-leak)
    start_jvm "$MODE"
    for i in 1 2 3; do api "/victim/health" >/dev/null; sleep 1; done
    echo "    leaking 80 victim-worker threads"; api "/victim/leak-threads?count=80"; echo
    sleep 3
    BETWEEN="curl -s \"http://127.0.0.1:$PORT/victim/leak-threads?count=60\""
    finish "$MODE"
    ;;

  incident-exceptions)
    start_jvm "$MODE"
    api "/victim/health" >/dev/null
    echo "    firing recurring failures (the endpoint answers HTTP 500 on purpose)"
    api "/victim/errors?count=30" >/dev/null; echo "    first burst done"
    api "/victim/errors?count=24" >/dev/null; echo "    second burst done"
    sleep 2
    DELAY=3
    finish "$MODE"
    ;;

  healthy)
    start_jvm "$MODE"
    api "/victim/health" >/dev/null
    echo "    clean traffic only: bounded short-lived allocations, no threads, no monitors, no errors"
    for i in 1 2 3 4; do
      api "/victim/healthy?iterations=2500&payload=16384"; echo
      sleep 2
      api "/victim/health" >/dev/null
    done
    sleep 3
    finish "$MODE"
    ;;

  *) echo "generate-corpus.sh: unknown mode '$MODE'" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- final inventory
echo
echo "================ corpus inventory ================"
for MODE in "${MODES[@]}"; do
  echo "-- $MODE"
  for f in threads.dump threads-2.dump heap.histo gc.log app.log TRUTH.md; do
    p="$CORPUS/$MODE/$f"
    [ -f "$p" ] && printf '   %-16s %10s bytes\n' "$f" "$(wc -c < "$p" | tr -d ' ')"
  done
done
echo
echo "one-off capture from a live JVM:  scripts/capture.sh -o corpus/incident-deadlock"
