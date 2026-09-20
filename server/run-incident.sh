#!/usr/bin/env bash
# One-shot "reproduce an incident" driver for the compose stack.
#
#   INCIDENT=deadlock  ->  GET /victim/deadlock   -> corpus/incident-deadlock/
#   ...
#
# It triggers the victim over HTTP and then runs scripts/capture.sh against that JVM. For the
# JDK tools to attach, this container must share the victim container's PID namespace and /tmp -
# that is what `pid: "host"` and the `jvm-tmp` volume do in docker-compose.yml.
set -euo pipefail
: "${VICTIM_URL:=http://victim:8080}"
: "${ARTIFACTS_DIR:=/artifacts}"

INCIDENT="${1:-${INCIDENT:-deadlock}}"

case "$INCIDENT" in
  deadlock)    DIR=incident-deadlock;   TRIGGER=(curl -fsS "$VICTIM_URL/victim/deadlock") ;;
  heap-leak)   DIR=incident-heap-leak;  TRIGGER=(sh -c 'for i in $(seq 1 8); do
                  used=$(curl -fsS "'"$VICTIM_URL"'/victim/health" | sed -n '"'"'s/.*"heapUsedMb":\([0-9]*\).*/\1/p'"'"');
                  max=$(curl -fsS "'"$VICTIM_URL"'/victim/health" | sed -n '"'"'s/.*"heapMaxMb":\([0-9]*\).*/\1/p'"'"');
                  [ "$((used * 100))" -ge "$((max * 78))" ] && break
                  curl -fsS "'"$VICTIM_URL"'/victim/leak?mb=24&rows=50000" >/dev/null; sleep 2; done') ;;
  gc-storm)    DIR=incident-gc-storm;   TRIGGER=(curl -fsS "$VICTIM_URL/victim/gcstorm?rounds=2000&workingSetMb=175") ;;
  thread-leak) DIR=incident-thread-leak; TRIGGER=(sh -c "curl -fsS '$VICTIM_URL/victim/leak-threads?count=80' >/dev/null; sleep 3; curl -fsS '$VICTIM_URL/victim/leak-threads?count=60' >/dev/null") ;;
  exceptions)  DIR=incident-exceptions;  TRIGGER=(sh -c "curl -fsS '$VICTIM_URL/victim/errors?count=30' >/dev/null; curl -fsS '$VICTIM_URL/victim/errors?count=24' >/dev/null") ;;
  healthy)     DIR=healthy;              TRIGGER=(sh -c "for i in 1 2 3 4; do curl -fsS '$VICTIM_URL/victim/healthy?iterations=2500&payload=16384' >/dev/null; sleep 2; done") ;;
  *) echo "run-incident.sh: unknown incident '$INCIDENT' (deadlock|heap-leak|gc-storm|thread-leak|exceptions|healthy)" >&2; exit 2 ;;
esac

echo "==> waiting for $VICTIM_URL/victim/health"
for _ in $(seq 1 60); do
  curl -fsS "$VICTIM_URL/victim/health" >/dev/null 2>&1 && break
  sleep 2
done

echo "==> triggering incident: $INCIDENT"
"${TRIGGER[@]}" >/dev/null 2>&1 || echo "    (trigger returned non-zero - expected for 'exceptions', which answers HTTP 500)"
sleep 5

echo "==> capturing"
# --skip-histo: jmap inside a sidecar needs the same /tmp + PID namespace; if it is unavailable
# the histogram is the only artifact you lose, so the run still produces something reviewable.
exec sh /opt/demo-victim/scripts/capture.sh \
  -o "$ARTIFACTS_DIR/$DIR" \
  -m 'demo-victim' \
  -d 6 \
  --gc-log "$GC_LOG_FILE" \
  --app-log "$APP_LOG_FILE"
