# Server-model performance study

A small Java codebase used to compare three server models on the same
workload:

1. **Threaded** — `ThreadedServer` with a fixed thread pool (one thread
   per connection, blocking I/O).
2. **NIO-Async** — `NioServerService` with a reactor pipeline + a fixed
   handler pool. Same idea as Netty / Tomcat NIO / Vert.x worker pool.
3. **Loom Threaded** — `ThreadedServer` with
   `Executors.newVirtualThreadPerTaskExecutor()`. No NIO; one virtual
   thread per connection.

The point is to **measure** when each model breaks, by how much, and
what each one costs. The benchmark plan and how to run it are in
[docs/BENCHMARK.md](docs/BENCHMARK.md).

Not for production use.

## Repository layout

```
com.jun.Server                 main entry point (picks transport from server.type)
com.jun.config.ServerConfig    typed config loaded from server.properties
com.jun.http                   parser, request, response, router — no I/O
com.jun.app                    sample handlers (hello, echo, sleep)

com.jun.threadedServer         ThreadedServer + HttpWorker
com.jun.nioServer              NioServerService + IOReactor + ConnectionRegistrar
com.jun.nioServer.message      MessageReader / MessageProcessor / AsyncMessageProcessor
com.jun.nioServer.io           SocketReader / SocketWriter
com.jun.nioServer.ssl          SSLEngineBuffer (NIO-side TLS)
com.jun.ssl.SslContextFactory  shared SSL context builder
```

Both transports run the same `HttpRequestHandler`. Both can run a
binary protocol — implement `MessageReader` + `MessageProcessor` (NIO)
or `WorkerFactory` (threaded).

The NIO transport is a multi-stage reactor:

```
accept  →  ConnectionRegistrar pool  →  IOReactor[i] (select)
        (SSL handshake)               →  I/O pool (SocketReader / SocketWriter)
                                      →  MessageDispatcher
                                      →  MessageProcessor (sync or async)
```

The selector thread does no application work. With
`nio.async.dispatch.enabled=true`, the handler runs on a separate
executor (default `fixed:500`), so a blocking handler does not stall
reads or writes.

## Build

```bash
mvn test       # unit tests
mvn package    # unit + fat jar
mvn verify     # unit + integration (*IT — real server, no Docker)
```

Requires JDK 21+. Tests use Mockito 4. First build downloads dependencies;
subsequent builds work offline with `mvn -o <goal>`.

To pin the Maven version for everyone who clones the repo, generate the
wrapper once (requires network):

```bash
mvn wrapper:wrapper -Dmaven=3.9.6
```

After that `./mvnw <goal>` works without a system mvn.

## Run

```bash
java -jar target/simpleNioServer-0.1-SNAPSHOT.jar             # default: NIO
java -Dserver.type=threaded -jar target/simpleNioServer-0.1-SNAPSHOT.jar
java -Dserver.type=virtual  -jar target/simpleNioServer-0.1-SNAPSHOT.jar
```

The jar is a fat jar (Main-Class set, all dependencies bundled). The
thin jar is preserved at `target/original-simpleNioServer-0.1-SNAPSHOT.jar`.

Configuration: `src/main/resources/server.properties`. Any key can be
overridden with `-Dkey=value` at startup.

```bash
curl http://localhost:8080/hello                   # "Hello World"
curl http://localhost:8080/echo                    # echoes the URI
curl -d "payload" http://localhost:8080/echo       # echoes the body
curl http://localhost:8080/sleep                   # /sleep handler (latency = -Dbench.sleep.ms)
curl http://localhost:8080/missing                 # 404
```

## Benchmark

See [docs/BENCHMARK.md](docs/BENCHMARK.md). Three profiles:

```bash
ulimit -n 20000
docker compose build

# Quick sanity (~2 min) — does the server run?
scripts/run-matrix.sh --profile quick

# Directional answers (~20 min) — C10K? NIO improves? Loom effective?
scripts/run-matrix.sh --profile minimal
scripts/format-results.py results/raw --out results/report.md

# Precision (~4-5 h) — all experiments, more reps.
scripts/run-matrix.sh --profile full
```
