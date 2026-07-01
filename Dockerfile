# syntax=docker/dockerfile:1
#
# Server image — builds and runs the benchmark server on JDK 21. The codebase
# itself still targets Java 8 (so unit tests on the host with old Mockito keep
# working), but the runtime container is JDK 21 so virtual threads work.
#
# Transport selection at run time:
#   docker run -e SERVER_TYPE=nio       ...   (default)
#   docker run -e SERVER_TYPE=threaded  ...
#   docker run -e SERVER_TYPE=virtual   ...
#
# Handler latency for the /sleep route (E3, E7):
#   docker run -e SLEEP_MS=50 ...
#
# NIO reactor count for the test profile:
#   docker run -e NUM_REACTORS=2 ...

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
# Pre-fetch dependencies so source changes don't re-download the world.
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/simpleNioServer-*.jar /app/app.jar
COPY src/main/resources/server.properties /app/server.properties
COPY src/main/resources/server.jks /app/server.jks
COPY src/main/resources/trustedCerts.jks /app/trustedCerts.jks

EXPOSE 8080

ENV SERVER_TYPE=nio \
    SLEEP_MS=0 \
    NUM_REACTORS=2 \
    POOL_SIZE=100 \
    BACKLOG=128 \
    SSL_ENABLED=false \
    HEAP=256m \
    ASYNC_DISPATCH=false \
    ASYNC_DISPATCH_EXECUTOR=fixed:500

# JVM flags pinned for reproducible benchmark runs:
#   * fixed heap (no resize noise)
#   * G1 GC (consistent baseline)
#   * tracePinnedThreads=full (Layer-5 metric for the Virtual transport)
#   * GC log to stderr for parsing by collect-server-metrics.sh
ENTRYPOINT ["/bin/sh","-lc","exec java \
    -Xms${HEAP} -Xmx${HEAP} \
    -XX:+UseG1GC \
    -Xlog:gc=info::time \
    -Djdk.tracePinnedThreads=full \
    -Dserver.type=${SERVER_TYPE} \
    -Dserver.address=0.0.0.0 \
    -Dserver.backlog=${BACKLOG} \
    -Dbench.sleep.ms=${SLEEP_MS} \
    -Dnio.num.io.reactors=${NUM_REACTORS} \
    -Dnio.async.dispatch.enabled=${ASYNC_DISPATCH} \
    -Dnio.async.dispatch.executor=${ASYNC_DISPATCH_EXECUTOR} \
    -Dthreaded.server.pool.size=${POOL_SIZE} \
    -Dssl.enabled=${SSL_ENABLED} \
    -Dssl.keystore.path=/app/server.jks \
    -Dssl.truststore.path=/app/trustedCerts.jks \
    -jar /app/app.jar"]
