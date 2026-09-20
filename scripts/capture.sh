#!/usr/bin/env bash
# capture.sh - grab the four JVM incident artifacts from a running victim JVM.
#
#   threads.dump   jstack -l        (instant A)
#   threads-2.dump jstack -l        (instant B, --delay seconds later -> multi-dump rules)
#   heap.histo     jmap -histo:live
#   gc.log         copy of the JVM's own -Xlog:gc*:file= output (path auto-detected or --gc-log)
#   app.log        copy of the application stdout/log file                       (or --app-log)
#
# Needs nothing but JAVA_HOME (jps/jstack/jmap/jcmd). Works in Git Bash on Windows, in WSL and
# in a Linux container. Docker is not required.
#
# Usage:
#   scripts/capture.sh -o corpus/incident-deadlock [-p PID] [-m MATCH] [-d 5]
#                      [--gc-log FILE] [--app-log FILE] [--between-cmd 'curl ...'] [--skip-histo]

set -uo pipefail

usage() {
  sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
}

OUT="."
PID=""
MATCH="demo-victim"
DELAY=5
GC_LOG=""
APP_LOG=""
BETWEEN_CMD=""
SKIP_HISTO=0

while [ $# -gt 0 ]; do
  case "$1" in
    -o|--out)        OUT="$2"; shift 2 ;;
    -p|--pid)        PID="$2"; shift 2 ;;
    -m|--match)      MATCH="$2"; shift 2 ;;
    -d|--delay)      DELAY="$2"; shift 2 ;;
    --gc-log)        GC_LOG="$2"; shift 2 ;;
    --app-log)       APP_LOG="$2"; shift 2 ;;
    --between-cmd)   BETWEEN_CMD="$2"; shift 2 ;;
    --skip-histo)    SKIP_HISTO=1; shift ;;
    -h|--help)       usage ;;
    *) echo "capture.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

# ---------------------------------------------------------------- toolchain resolution
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "${JAVA_HOME:-}" ]; then
  # fall back to whatever `java` is on PATH
  if command -v java >/dev/null 2>&1; then
    JAVA_HOME="$(cd "$(dirname "$(command -v java)")/.." && pwd)"
  fi
fi
if [ ! -d "$JAVA_HOME/bin" ]; then
  echo "capture.sh: JAVA_HOME ($JAVA_HOME) has no bin/ - set JAVA_HOME to a JDK (not a JRE)" >&2
  exit 3
fi

tool() { # tool jstack -> absolute path incl. .exe on Windows
  local name="$1"
  if [ -x "$JAVA_HOME/bin/$name" ]; then printf '%s' "$JAVA_HOME/bin/$name"
  elif [ -x "$JAVA_HOME/bin/$name.exe" ]; then printf '%s' "$JAVA_HOME/bin/$name.exe"
  else echo "capture.sh: $name not found under $JAVA_HOME/bin" >&2; exit 3
  fi
}

JPS="$(tool jps)"; JSTACK="$(tool jstack)"; JMAP="$(tool jmap)"; JCMD="$(tool jcmd)"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

norm() {
  if command -v realpath >/dev/null 2>&1; then realpath -- "$1" 2>/dev/null || printf '%s' "$1"
  else printf '%s' "$1"; fi
}
same_file() {
  [ -n "$1" ] && [ -n "$2" ] || return 1
  local a b
  a="$(norm "$1")"; b="$(norm "$2")"
  [ "$(printf '%s' "$a" | tr 'A-Z' 'a-z')" = "$(printf '%s' "$b" | tr 'A-Z' 'a-z')" ]
}

# ---------------------------------------------------------------- find the pid
if [ -z "$PID" ]; then
  PIDS="$("$JPS" -l 2>/dev/null | grep -vi -e '^\$' -e 'Jps' -e 'sun.tools.jps' | grep -i -e "$MATCH" -e 'dev\.jingyu\.jia\.victim' || true)"
  if [ -z "$PIDS" ]; then
    echo "capture.sh: no JVM matching '$MATCH' - is demo-victim running? (jps -l output below)" >&2
    "$JPS" -l >&2 || true
    exit 4
  fi
  PID="$(printf '%s\n' "$PIDS" | head -1 | awk '{print $1}')"
  if [ "$(printf '%s\n' "$PIDS" | wc -l)" -gt 1 ]; then
    echo "capture.sh: several matching JVMs, picking pid=$PID; pass -p to choose:" >&2
    printf '%s\n' "$PIDS" | sed 's/^/    /' >&2
  fi
fi
if ! "$JPS" -l | grep -q "^$PID[  ]"; then
  echo "capture.sh: pid $PID is not a running JVM according to jps" >&2
  exit 4
fi
echo "==> target JVM pid $PID ($("$JPS" -l | grep "^$PID[  ]" | cut -d' ' -f2-))"

# ---------------------------------------------------------------- auto-detect log paths
DECOR='time|uptime|ticks|ttlt|thread|pid|level|tags|hostname|date|rt|message|all'
detect_gc_log() {
  # -Xlog:gc*:file=<path>[:decorators[:outputparams]]  ->  <path>
  # <path> may be relative (to the JVM's own cwd) or carry a Windows drive letter, so the
  # decorations are stripped by name rather than by splitting on ':' (a drive letter has one).
  local spec rest userdir guess
  spec="$("$JCMD" "$PID" VM.command_line 2>/dev/null | tr ' ' '\n' | grep -- '-Xlog' | grep -- 'file=' | head -1)"
  [ -n "$spec" ] || return 1
  rest="${spec#*file=}"
  rest="$(printf '%s' "$rest" | sed -E "s/:($DECOR)(,($DECOR))*(:[^:]*)?$//")"
  [ -n "$rest" ] || return 1
  case "$rest" in
    */*|*\\*|*[A-Za-z]:*) printf '%s' "$rest"; return 0 ;;   # already absolute-ish
  esac
  # relative: resolve against the target JVM's own user.dir
  userdir="$("$JCMD" "$PID" VM.system_properties 2>/dev/null | sed -n 's/^user\.dir=//p' | head -1)"
  userdir="$(printf '%s' "$userdir" | tr '\\' '/')"
  [ -n "$userdir" ] || { printf '%s' "$rest"; return 0; }
  guess="$userdir/$rest"
  if [ -f "$guess" ]; then printf '%s' "$guess"; else printf '%s' "$rest"; fi
}
[ -n "$GC_LOG" ] || GC_LOG="$(detect_gc_log || true)"
if [ -n "$GC_LOG" ] && [ ! -f "$GC_LOG" ]; then
  echo "capture.sh: GC log '$GC_LOG' not readable from here (auto-detected or --gc-log); pass --gc-log" >&2
fi

# ---------------------------------------------------------------- dump 1
echo "==> jstack -l -> $OUT/threads.dump"
"$JSTACK" -l "$PID" > "$OUT/threads.dump" 2>&1 || { echo "capture.sh: jstack failed" >&2; cat "$OUT/threads.dump" >&2; exit 5; }

sleep "$DELAY"
if [ -n "$BETWEEN_CMD" ]; then
  echo "==> between-cmd: $BETWEEN_CMD"
  sh -c "$BETWEEN_CMD" >/dev/null 2>&1 || echo "capture.sh: between-cmd exited non-zero (ignored)" >&2
  sleep 2
fi

echo "==> jstack -l -> $OUT/threads-2.dump"
"$JSTACK" -l "$PID" > "$OUT/threads-2.dump" 2>&1 || echo "capture.sh: second jstack failed (continuing)" >&2

# ---------------------------------------------------------------- gc.log + app.log
# copied BEFORE the histogram on purpose: `jmap -histo:live` forces one full collection of its
# own, and that entry belongs to the tooling, not to the incident the analyzer should read.
copy_in() { # copy_in <src> <name> <human>
  local src="$1" name="$2" what="$3"
  if [ -z "$src" ]; then
    echo "    !! no $what found: look for it via \"$(basename "$JCMD") $PID VM.command_line\"" >&2
    return 1
  fi
  if same_file "$src" "$OUT/$name"; then
    echo "    == $name already belongs to the target JVM at $src (left in place)"
    return 0
  fi
  cp -f "$src" "$OUT/$name" && echo "    -> copied $what from $src"
}
copy_in "$GC_LOG"  "gc.log"  "GC log"
if [ -z "$APP_LOG" ]; then
  # the JVM's own working directory first (that is where a `> app.log` redirect landed), then the
  # usual conventions from wherever capture.sh happens to be run
  USERDIR="$("$JCMD" "$PID" VM.system_properties 2>/dev/null | sed -n 's/^user\.dir=//p' | head -1 | tr '\\' '/')"
  for cand in "${USERDIR:+$USERDIR/app.log}" "${USERDIR:+$USERDIR/logs/app.log}" \
              "$OUT/../logs/app.log" "$(pwd)/app.log" "$(pwd)/logs/app.log"; do
    [ -n "$cand" ] && [ -f "$cand" ] && { APP_LOG="$cand"; break; }
  done
fi
copy_in "$APP_LOG" "app.log" "application log"

# ---------------------------------------------------------------- histogram
if [ "$SKIP_HISTO" -eq 0 ]; then
  echo "==> jmap -histo:live -> $OUT/heap.histo   (forces one full GC in the target JVM)"
  "$JMAP" -histo:live "$PID" > "$OUT/heap.histo" 2>&1 || echo "capture.sh: jmap failed" >&2
else
  echo "==> skipping heap histogram (--skip-histo)"
fi

# ---------------------------------------------------------------- what did we get?
echo
echo "==> wrote $OUT"
for f in threads.dump threads-2.dump heap.histo gc.log app.log; do
  if [ -f "$OUT/$f" ]; then
    printf '    %-16s %10s bytes  %8s lines\n' "$f" "$(wc -c < "$OUT/$f" | tr -d ' ')" "$(wc -l < "$OUT/$f" | tr -d ' ')"
  else
    printf '    %-16s (absent)\n' "$f"
  fi
done

echo
echo "==> evidence quick-check"
check() { # check <file> <label> <grep -E pattern>
  local f="$OUT/$1" label="$2" pat="$3" n=0
  [ -f "$f" ] && n="$(grep -c -E "$pat" "$f" 2>/dev/null || true)"
  printf '    %-34s %s\n' "$label" "${n:-0}"
}
check threads.dump   "deadlocks in dump 1"        'Found one Java-level deadlock'
check threads-2.dump "deadlocks in dump 2"        'Found one Java-level deadlock'
check threads.dump   "BLOCKED threads (dump 1)"   'java\.lang\.Thread\.State: BLOCKED'
check threads.dump   "victim-worker threads (1)"  '"victim-worker-'
check threads-2.dump "victim-worker threads (2)"  '"victim-worker-'
check threads.dump   "total threads in dump 1"    '^"'
check gc.log         "Full GC pauses"             'Pause Full'
check gc.log         "concurrent cycles"          'Concurrent Mark Cycle'
check gc.log         "young pauses"               'Pause Young'
check heap.histo     "histogram rows"             '^ *[0-9]+:'
check app.log        "ERROR lines"                ' ERROR '
check app.log        "caused-by chains"           'Caused by:'
exit 0
