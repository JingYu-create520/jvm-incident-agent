#!/usr/bin/env bash
# Starts demo-victim with the flags the corpus and the analyzer tests are tuned against, and
# keeps the two log-shaped artifacts (gc.log, app.log) inside $ARTIFACTS_DIR so a mounted volume
# carries them out of the container.
#
# Extra JVM flags can be injected with JVM_OPTS (word-split on purpose).
set -euo pipefail
: "${ARTIFACTS_DIR:=/artifacts}"
: "${GC_LOG_FILE:=$ARTIFACTS_DIR/gc.log}"
: "${APP_LOG_FILE:=$ARTIFACTS_DIR/app.log}"
: "${JVM_XMS:=256m}"
: "${JVM_XMX:=256m}"
: "${HTTP_PORT:=8080}"
mkdir -p "$ARTIFACTS_DIR" "$(dirname "$GC_LOG_FILE")" "$(dirname "$APP_LOG_FILE")"

# GC_LOG_FILE may be absolute or relative; JDK 17 tolerates the drive-letter colon in a Windows
# path, but the JVM will not start if the directory does not exist - hence the mkdir above.
exec java \
  -Xms"$JVM_XMS" -Xmx"$JVM_XMX" -XX:+UseG1GC \
  "-Xlog:gc*:file=$GC_LOG_FILE:time,uptime,level,tags" \
  -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  ${JVM_OPTS:-} \
  -jar /opt/demo-victim/demo-victim.jar --server.port="$HTTP_PORT" \
  > >(tee -a "$APP_LOG_FILE") 2>&1
