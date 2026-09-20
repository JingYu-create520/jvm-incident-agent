# syntax=docker/dockerfile:1
# -----------------------------------------------------------------------------
# demo-victim: the "target" half of jvm-incident-agent.
#
# Build context is the REPOSITORY ROOT (see server/docker-compose.yml), because this image
# needs both demo-victim/ (the app) and scripts/ (the capture tooling).
#
#   docker build -f server/Dockerfile -t jvm-incident-agent/demo-victim:local .
#
# The final stage is a JDK, not a JRE, on purpose: scripts/capture.sh reaches for
# jps/jstack/jmap/jcmd under JAVA_HOME/bin, and the `capture` service in docker-compose.yml
# runs inside this same image.
# -----------------------------------------------------------------------------

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# dependencies first, so the layer cache survives source edits
COPY demo-victim/pom.xml demo-victim/pom.xml
RUN mvn -B -q -f demo-victim/pom.xml dependency:go-offline
COPY demo-victim/src demo-victim/src
RUN mvn -B -q -f demo-victim/pom.xml package -DskipTests \
 && cp demo-victim/target/demo-victim.jar /opt/demo-victim.jar

FROM eclipse-temurin:17-jdk-jammy AS runtime

# curl: the endpoints are triggered over HTTP and it backs the container healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl procps \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/demo-victim
COPY --from=build /opt/demo-victim.jar /opt/demo-victim/demo-victim.jar
COPY scripts /opt/demo-victim/scripts
COPY server/docker-entrypoint.sh server/run-incident.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh /usr/local/bin/run-incident.sh \
 && mkdir -p /artifacts

# the incident only bites on a capped heap; override per run if you want to prove that
ENV JVM_XMS=256m \
    JVM_XMX=256m \
    HTTP_PORT=8080 \
    ARTIFACTS_DIR=/artifacts \
    GC_LOG_FILE=/artifacts/gc.log \
    APP_LOG_FILE=/artifacts/app.log \
    JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8

EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=5s --start-period=25s --retries=40 \
  CMD curl -fsS "http://127.0.0.1:${HTTP_PORT}/victim/health" >/dev/null || exit 1

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
