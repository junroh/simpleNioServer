# Benchmark: Threaded vs NIO-Async vs Loom Threaded

A study comparing three server models on the same workload. The goal is
to see how each one behaves, where each one stops scaling, and what the
trade-offs look like — backed by numbers, not opinion.

## Models

1. **Threaded** — `ThreadedServer` with a fixed thread pool.
   Thread-per-connection, blocking I/O. The classic model.

2. **NIO-Async** — `NioServerService` with
   `nio.async.dispatch.enabled=true` and
   `nio.async.dispatch.executor=fixed:500`. Reactor pipeline + handler
   invocation offloaded onto a fixed platform-thread pool of 500. The
   classic reactor + worker-pool pattern (Netty `DefaultEventExecutorGroup`,
   Tomcat NIO, Vert.x worker verticles). **No virtual threads.**

3. **Loom Threaded** — `ThreadedServer` with
   `Executors.newVirtualThreadPerTaskExecutor()`. Thread-per-connection
   code, but each connection runs on a virtual thread. No NIO.

## Questions

1. Where does Threaded stop scaling?
2. How much does NIO-Async push past Threaded?
3. How does Loom Threaded compare to NIO-Async?
4. What does each model cost?

## What differs between the three

| Aspect              | Threaded                          | NIO-Async                                                | Loom Threaded                            |
| ------------------- | --------------------------------- | -------------------------------------------------------- | ---------------------------------------- |
| Threads per conn    | 1 from a fixed pool (default 100) | 0 transport threads; handler uses 1 from a separate pool of 500 | 0 platform threads; 1 VT per conn |
| Carrier OS threads  | poolSize (100)                    | 2 reactors + I/O pools + handler pool (500)              | `Runtime.availableProcessors()` (≈ 2)    |
| Where blocking goes | worker thread (held until done)   | handler pool thread (held until done)                    | VT (carrier unmounts on block)           |
| Handler runs on     | the connection's worker thread    | a thread from the fixed handler pool                     | the connection's virtual thread          |
| Saturates when      | `c > poolSize`                    | `c > handlerPool` AND handlers block                     | carrier CPUs full or VT pinning          |

The shared property of NIO-Async and Loom Threaded: handler latency
does not block the transport's accept / read / select loops. NIO-Async
achieves this by isolating the reactor from the handler pool; Loom by
unmounting the carrier when the VT blocks. Their *handler ceilings*
differ, though — NIO-Async is bounded by `handlerPool` size,
Loom by the number of carrier CPUs.

## Hypotheses

These are expectations, not pass/fail rules. If a result differs from
the expectation, the difference is the finding.

| ID | Expectation                                                                                       | Where  |
| -- | ------------------------------------------------------------------------------------------------- | ------ |
| H1 | At c=50 with an instant handler, the three transports are within ~15% of each other (setup OK). | E1     |
| H2 | As c grows on instant handlers, Threaded plateaus first; NIO-Async and Loom keep scaling.       | E2     |
| H3 | Under blocking handlers, throughput ≈ parallel slots × (1 / latency). Threaded slots = `poolSize` (100); NIO-Async slots = `min(c, handlerPool)` (500); Loom slots = `c`. | E3     |
| H4 | HTTPS hurts Threaded more than the other two (handshake holds a worker).                        | E4     |
| H5 | Disabling keep-alive narrows the gaps but does not invert them.                                  | E5     |
| H6 | With 10 000 idle keep-alive connections, Threaded fails at poolSize; the other two hold all 10 000. | E6 |
| H7 | At c above poolSize with a 50 ms blocking handler, Threaded shows idle CPU + timeouts.          | E7     |
| H8 | NIO-Async and Loom both remove the H7 *symptom* (errors=0, latency stays near `L`). Their *throughput ceilings* differ: NIO-Async ≈ `handlerPool / L`, Loom ≈ `c / L`. | E7 |

H7 reproduces the failure mode this study cares about. H8 is the
head-to-head comparison — both transports clear the symptom, but at
different throughput ceilings (`handlerPool / L` vs `c / L`). The gap
is the cost of *not* using Loom.

## Metrics

Five layers per scenario. Layer 1 is the headline; the others explain it.

### Layer 1 — client (wrk2)
- Throughput (req/s)
- p50, p99, p99.9 (coordinated-omission-safe)
- Errors (timeouts, resets, non-2xx)

### Layer 2 — server resource
- CPU% (`docker stats`)
- RSS MB (`docker stats`)
- JVM thread count (`jcmd Thread.print | wc -l`)
- GC pause total (JVM log)

### Layer 3 — kernel
- Voluntary context switches (`/proc/<pid>/status`)
- Open fd count
- TCP socket states (`ss -s`)

### Layer 4 — ratios
- Throughput per CPU%
- NIO-Async / Threaded
- Loom / Threaded
- Loom / NIO-Async  (this is the H8 number)
- p99 / p50
- Idle-CPU-with-errors: 1 if CPU% < 50 AND timeouts > 1%, else 0

### Layer 5 — thread snapshot (one per scenario)

`jcmd <pid> Thread.print > threads-${exp}-${transport}-${c}.txt`

Count by transport:

| Transport      | Bucket                                  | Meaning                                  |
| -------------- | --------------------------------------- | ---------------------------------------- |
| Threaded       | RUNNABLE                                | actually on CPU                          |
| Threaded       | TIMED_WAITING in `Thread.sleep`         | blocked on handler                       |
| NIO-Async      | dispatcher state                        | should be selecting / waiting, not in handler |
| NIO-Async      | VT carriers                             | ≈ cpus                                   |
| NIO-Async      | pinned events                           | from `-Djdk.tracePinnedThreads=full`; 0 expected |
| Loom Threaded  | carrier threads (`ForkJoinPool-*`)      | ≈ cpus                                   |
| Loom Threaded  | mounted VTs                             | how many VTs are on CPU                  |
| Loom Threaded  | unmounted VTs                           | the idle connections                     |
| Loom Threaded  | pinned events                           | non-zero erases Loom's advantage         |

## Experiments

Common controls:

- Server: Docker, `--cpus=2 --memory=512m`, JDK 21, G1 GC,
  `-Xms256m -Xmx256m`, `-Djdk.tracePinnedThreads=full`.
- Load generator: wrk2, separate container, same host network.
- Threaded pool size: 100. NIO `numIoReactors`: 2,
  `numIoThreads`: 8.
  Async dispatch executor: `fixed:500` (platform threads, no Loom).
  (These are pinned for the run matrix; the shipped defaults are smaller — see
  `server.properties`.)

Warmup, measured duration, and reps are set by the profile, not fixed:

| Profile | Warmup | Measured | Reps | Experiments |
|---|---|---|---|---|
| `quick`   | 5 s  | 15 s | 1 | E1 |
| `minimal` | 15 s | 30 s | 2 | E7 (c ∈ {200, 1000, 2000}), E6 |
| `full`    | 30 s | 60 s | 5 | E1..E7 (full c sweeps) |

The expected-value tables below (E1..E7) assume the `full` profile's controls
(60 s measured, reps=5, median reported, outliers > 2× IQR dropped). `quick` and
`minimal` trade precision for speed, so treat their numbers as directional.

Which profile answers which hypothesis:

- `quick` → H1 (setup sane / all three respond).
- `minimal` → H6, H7, H8 (the C10K failure mode and the NIO-Async-vs-Loom
  head-to-head — the questions this study exists for).
- `full` → all of H1..H8.

### E1 — baseline

c=50, instant handler (`/hello`), HTTP, keep-alive on.

Expected: all three at ~30 000 r/s, p99 < 5 ms, CPU 60–90%.

Compare: are the three within ~15%? If not, fix the setup before
reading other experiments.

### E2 — concurrency sweep, instant handler

c ∈ {50, 200, 1000, 5000}, instant handler, HTTP.

Expected throughput (r/s):

| c    | Threaded | NIO-Async | Loom    | NIO-Async/T | Loom/T |
| ---- | -------- | --------- | ------- | ----------- | ------ |
| 50   | ~30 000  | ~28 000   | ~30 000 | ~0.93       | ~1.00  |
| 200  | ~50 000  | ~55 000   | ~55 000 | ~1.10       | ~1.10  |
| 1000 | ~40 000  | ~70 000   | ~65 000 | ~1.75       | ~1.60  |
| 5000 | ~20 000  | ~75 000   | ~60 000 | ~3.75       | ~3.00  |

Compare: where does Threaded plateau? How fast do the ratios grow?

### E3 — handler latency sweep

c=1000, `Thread.sleep(L)` handler, L ∈ {0, 10, 50} ms, HTTP.

Predicted ceilings:
- Threaded: `poolSize / L = 100 / L` r/s
- NIO-Async: `min(c, handlerPool) / L = 500 / L` r/s (c=1000 > pool=500)
- Loom: `c / L = 1000 / L` r/s

Expected throughput (r/s):

| L     | Threaded | NIO-Async | Loom    |
| ----- | -------- | --------- | ------- |
| 0     | ~40 000  | ~70 000   | ~65 000 |
| 10 ms | ~10 000  | ~50 000   | ~50 000 |
| 50 ms | ~2 000   | ~10 000   | ~20 000 |

Compare:
- Does Threaded match `100 / L`?
- Does NIO-Async match `500 / L` (its handler-pool ceiling)?
- Does Loom match `1000 / L` (its c ceiling)?
- At L=50 ms, what is Loom / NIO-Async? Expected ~2× (the gap is the
  cost of using a fixed pool instead of VTs).

### E4 — HTTP vs HTTPS

c=1000, instant handler, HTTP then HTTPS.

Expected throughput (r/s):

| Protocol | Threaded | NIO-Async | Loom    |
| -------- | -------- | --------- | ------- |
| HTTP     | ~40 000  | ~70 000   | ~65 000 |
| HTTPS    | ~3 000   | ~12 000   | ~10 000 |

Compare: does the (NIO-Async / Threaded) ratio grow under HTTPS? Does
Loom track NIO-Async or fall behind (handshake native pin)?

### E5 — connection churn

c=500, instant handler, HTTP, keep-alive **off**.

Expected throughput (r/s):

| Keep-alive | Threaded | NIO-Async | Loom    |
| ---------- | -------- | --------- | ------- |
| on         | ~50 000  | ~55 000   | ~55 000 |
| off        | ~12 000  | ~13 000   | ~13 500 |

Compare: are the gaps proportional, or does one model lose more?

### E6 — idle keep-alive fan-out

10 000 long-lived TCP connections, 100 req/s total, instant handler, HTTP.

Expected:

| Transport     | Outcome |
| ------------- | ------- |
| Threaded      | Bound at poolSize = 100. Accept queue fills, then refuses. |
| NIO-Async     | All 10 000 conns held by ~10 OS threads. 100 r/s at p99 ≤ 10 ms. |
| Loom Threaded | All 10 000 conns held by 10 000 VTs on ~2 carriers. 100 r/s at p99 ≤ 10 ms. |

Compare: Threaded error rate. RSS of NIO-Async vs Loom (per-VT stack
is Loom's cost; no prior claim on magnitude).

### E7 — production scenario

c ∈ {50, 100, 200, 500, 1000, 2000}, `Thread.sleep(50 ms)` handler,
HTTP, keep-alive on, pool size 100, accept backlog 128.

Expected (T = Threaded, A = NIO-Async, L = Loom):

| c    | T tps / err / p99       | A tps / err / p99       | L tps / err / p99       |
| ---- | ----------------------- | ----------------------- | ----------------------- |
| 50   | ~1 000 / 0 / 51 ms      | ~1 000 / 0 / 51 ms      | ~1 000 / 0 / 51 ms      |
| 100  | ~2 000 / 0 / 51 ms      | ~2 000 / 0 / 51 ms      | ~2 000 / 0 / 51 ms      |
| 200  | ~2 000 / 0 / 100 ms     | ~4 000 / 0 / 51 ms      | ~4 000 / 0 / 51 ms      |
| 500  | ~2 000 / some / 250 ms  | ~10 000 / 0 / 51 ms     | ~10 000 / 0 / 51 ms     |
| 1000 | ~2 000 / many / timeouts| **~10 000** / 0 / **~100 ms** (queueing past pool) | ~20 000 / 0 / 51 ms |
| 2000 | ~2 000 / many / timeouts| ~10 000 / 0 / **~200 ms** | ~20 000+ / 0 / 51 ms  |

NIO-Async plateaus at `handlerPool / L = 500 / 50 = 10 000` r/s. Past
that point excess requests queue (latency rises, but no errors — the
selector keeps accepting). Loom keeps scaling linearly with `c` until
carrier CPUs saturate.

Compare:
- At what c does Threaded cross into idle CPU + timeouts?
- Does NIO-Async hit its `handlerPool / L` ceiling? At what c does
  latency start rising past 51 ms?
- Does Loom keep scaling past that ceiling?
- The Loom / NIO-Async ratio at high c is the cost of *not* using Loom.

## Reading the result tables

`scripts/format-results.py` produces a single matrix:

```
exp  c    L   proto  transport   tps    p50  p99  p99.9  err%  cpu%  threads  ctxt/s  pinned
E1   50   0   HTTP   threaded    ...
E1   50   0   HTTP   nio-async   ...
E1   50   0   HTTP   virtual     ...
...
```

The three rows of each scenario are adjacent. Layer-4 ratios are
computed for each so you can read comparisons without doing math.

### Take-aways (fill in after running)

| Question | Source | What the numbers say |
| -------- | ------ | -------------------- |
| Where does Threaded hit a wall? | E2, E6 | _e.g. saturates at c≈1000 instant, fails at c=200 with 50 ms_ |
| How does Threaded fail? | E7 | _below pool size: latency rises linearly; above: timeouts, CPU stays low_ |
| How much does NIO-Async push past Threaded? | E2, E6, E7 | _e.g. 20× at c=1000 with 50 ms handler_ |
| Does NIO-Async itself have a ceiling? | E3 at L=50, E7 high c | _carrier-CPU bound; submit overhead at very high c_ |
| Does Loom escape the wall too? | E2, E6, E7 | _same as NIO-Async if pinned = 0_ |
| Is Loom as good as NIO-Async? | E3, E7 | _within ±30% expected; divergence is informative either way_ |
| Cost of each model | E1 (instant) | _Threaded: simple, hard ceiling. NIO-Async: reactor complexity. Loom: per-VT memory._ |

## Risks (things that fool the measurement)

| Risk | How it shows | What to do |
| ---- | ------------ | ---------- |
| JIT not warm | rep 1 lower than 2-5 | discard 30 s warmup; median of 5 reps |
| GC noise in p99 | p99 swings 10× between reps | fixed heap, G1, log GC pauses separately |
| Coordinated omission | wrk p99 unrealistically low under stalls | use `wrk2 -R` |
| Container CPU throttle | CPU% caps below `--cpus` | check `/sys/fs/cgroup/cpu.stat → nr_throttled` |
| Host contention | server + client on same host compete | host ≥ 6 cores |
| Load generator is the bottleneck | wrk2 -t < c/10 | set -t = c/10, min 2 |
| Localhost RTT bias | latencies < 1 ms | trust ratios, not absolute SLA numbers |
| Pool size varies between reps | unequal | pin to 100, verify with jcmd |
| Accept backlog hides Threaded saturation | Threaded "succeeds" under H7 | set server.backlog=128 |
| numIoReactors hides NIO-Async behaviour | E3 doesn't show carrier ceiling | pin to 2 |
| Client fd exhaustion | E6 fails for wrong reason | `ulimit -n 20000` |
| Async-dispatch flag not propagating | NIO-Async behaves like sync NIO | grep server log for "NIO async dispatch enabled" |
| ConnectionRegistrar pool too small under E4 | spurious HTTPS handshake timeouts | raise `nio.registrar.pool.size` |
| VT pinning | Loom advantage vanishes in E3/E7 | check `-Djdk.tracePinnedThreads=full` output |
| VT carrier starvation | Loom tps flat early; CPU saturated | check ForkJoinPool worker count |
| VT stack memory | RSS climbs sharply under E6 | record RSS at 10 000 conns; compare to NIO-Async |
| JDK version mismatch | older JDK pins on more I/O ops | JDK 21+ enforced in Dockerfile |

## Out of scope

- Production absolute numbers (single host, container, localhost).
- Real network behaviour (no RTT, no packet loss).
- Realistic TLS cost at scale (self-signed RSA-2048).
- Memory leaks (60 s is too short).
- HTTP/2, WebSocket, pipelining.
- Cold-start latency.
- Tuning sweep over numIoReactors / numIoThreads.
- `nio.async.dispatch.executor=fixed:<N>` variant (follow-up if Loom
  unavailable or pins).
- Plain NIO (`nio.async.dispatch.enabled=false`). Supported by the code
  but a known bad default; running it does not add insight.
- Structured concurrency, scoped values.
- Mixed handler workloads (some sync, some blocking).

## Profiles

Three profiles with distinct purposes. Pick one for what you actually want.

| Profile  | Goal                                                | Scenarios | Settings                          | Time   |
| -------- | --------------------------------------------------- | --------- | --------------------------------- | ------ |
| quick    | sanity — does the server run at all?                | 3 (E1)    | warmup 5s, measure 15s, reps 1   | ~2 min |
| minimal  | directional — C10K visible? NIO improves? Loom effective? | 12 (E7 c∈{200, 1000, 2000} + E6) | warmup 15s, measure 30s, reps 2 | ~20 min |
| full     | precision — all experiments at higher reps          | 48 (E1..E7 full sweeps) | warmup 30s, measure 60s, reps 5 | ~4-5 h |

### What each profile answers

| Question | quick | minimal | full |
|---|---|---|---|
| Does the server start and answer? | ✓ | ✓ | ✓ |
| Does Threaded hit the C10K wall? | ✗ | ✓ (E7 c=1000) | ✓ |
| Does NIO-Async improve it? | ✗ | ✓ (E7 c=1000 ratio) | ✓ |
| Is Loom effective vs NIO-Async? | ✗ | ✓ (E7 c=2000 ceiling gap) | ✓ |
| Does Threaded fail on 10k idle conns? | ✗ | ✓ (E6) | ✓ |
| HTTPS / connection-churn effects? | ✗ | ✗ | ✓ (E4, E5) |
| p99.9 tail precision? | ✗ | ✗ | ✓ |

### Why minimal picks these scenarios

- **E7 c=200** — Threaded just past `poolSize=100`. Saturation starts here.
  If it doesn't, the pool isn't actually being hit.
- **E7 c=1000** — Threaded fully saturated (idle CPU + timeouts). NIO-Async
  still under its `handlerPool=500` ceiling. The headline ratio reads here.
- **E7 c=2000** — Above NIO-Async's handler pool. NIO-Async queues; Loom
  scales further. The Loom/NIO-Async gap quantifies the cost of using a
  fixed pool instead of virtual threads.
- **E6** — the C10K idle side. Threaded fails at poolSize; the other two
  hold all 10 000 connections.

E1..E5 are *not* in minimal because:

- E1 (baseline) — quick already does it.
- E2 (instant handler) — doesn't separate NIO-Async from Loom; both win
  over Threaded but the gap is uninteresting.
- E3 (handler latency sweep) — E7 at c=1000 already exercises blocking
  handler; E3 adds L=10 which is informative but not load-bearing.
- E4, E5 (HTTPS, churn) — secondary effects. Look at them in full when
  the headline is already understood.

## How to read minimal results

After minimal you should be able to fill in this table:

| Question | Where to look | What "yes" looks like |
|---|---|---|
| C10K visible on Threaded? | E7 c=1000, threaded row | `cpu% < 30 AND err% > 5` |
| NIO-Async improves throughput? | E7 c=1000, nio-async / threaded ratio | ratio ≥ 3 |
| NIO-Async eliminates timeouts? | E7 c=1000, nio-async row | `err% ≤ 1` |
| Loom matches NIO-Async at moderate c? | E7 c=1000, loom / nio-async ratio | within ±30% |
| Loom outscales NIO-Async at high c? | E7 c=2000, loom / nio-async ratio | ratio > 1 (NIO-Async hits pool ceiling) |
| Threaded fails on 10k idle conns? | E6, threaded row | err% > 50 |
| NIO-Async / Loom hold 10k idle? | E6, nio-async + virtual rows | err% ≤ 1 |

If quick or minimal exposes something unexpected, escalate that scenario
to full reps (or full profile). Don't run full as your first step — the
4-5 h is for verifying a result, not finding one.

## How to run

### Requirements

| What | Why | How to check |
|---|---|---|
| Docker 24+ with Compose v2 | `docker compose run`, per-service `cpus`/`mem_limit` | `docker compose version` |
| Host with ≥ 6 CPU cores | Server 2, load gen 2, host + kernel the rest | `nproc` / `sysctl -n hw.ncpu` |
| `ulimit -n ≥ 20 000` | E6 opens 10 000 idle sockets | `ulimit -n` |
| Internet (first build only) | JDK 21, wrk2 source, Maven deps | not needed after first build |
| ~2 GB disk | raw output for minimal profile | `df -h .` |

Free port `:8080` on the host.

### Setup

```bash
ulimit -n 20000
docker compose build
```

### Quick run (sanity check, ~2 min)

```bash
scripts/run-matrix.sh --profile quick --out-dir results/raw
```

Confirms that all three transports start and respond. No analysis value
beyond go/no-go.

### Minimal run (directional answers, ~20 min)

```bash
scripts/run-matrix.sh --profile minimal --out-dir results/raw
scripts/format-results.py results/raw --out results/report.md
```

12 scenarios (E7 at c ∈ {200, 1000, 2000} + E6) × 3 transports. Answers
the four study questions. reps=2 by default; bump with `--reps 3` if a
result looks noisy.

### Full run (precision, ~4-5 h)

```bash
scripts/run-matrix.sh --profile full --out-dir results/raw
scripts/format-results.py results/raw --out results/report-full.md
```

48 scenarios. Worth running only after minimal shows the signal you want
to lock down.

### Single experiment

```bash
scripts/run-matrix.sh --only E7 --profile minimal --out-dir results/raw

# Or one scenario directly:
scripts/bench.sh \
    --exp-id E7 --transport virtual --c 1000 --sleep-ms 50 \
    --rate 21000 --path /sleep --reps 3 --out-dir results/raw
```

### Allocation profiling (where do the bytes come from?)

Before any GC-reduction work, profile *where* the allocations are, rather than
guessing. `scripts/profile-alloc.sh` runs one scenario under load with a JFR
recording (`settings=profile`, so allocation sites are sampled) and — if a JDK
`jfr` tool is on the host — renders the top allocators:

```bash
scripts/profile-alloc.sh --transport nio --c 100 --rate 20000 --duration 60
# → results/alloc/alloc-nio/{alloc.jfr, alloc-views.txt, server.log, wrk2.txt}

# Analyse an existing recording by hand (JDK 21):
jfr view allocation-by-site  results/alloc/alloc-nio/alloc.jfr
jfr view allocation-by-class results/alloc/alloc-nio/alloc.jfr
jfr view gc                  results/alloc/alloc-nio/alloc.jfr
```

This perturbs throughput (JFR + sampling overhead), so it is **not** a
latency/throughput measurement — use it only for allocation attribution. It
works via the image's `EXTRA_JAVA_OPTS` passthrough, so the normal benchmark
runs above are unaffected. Compare transports by re-running with
`--transport threaded|nio-async|virtual` and a distinct `--exp-id`.


### Dry run

```bash
scripts/run-matrix.sh --profile minimal --dry-run
```

### Output

```
results/raw/
├── E1/
│   └── threaded_c50_L0_http/
│       ├── run.meta           # parameters
│       ├── warmup.txt         # discarded wrk2 output
│       ├── server.log         # server stdout/stderr (pinning + GC log)
│       ├── rep1/
│       │   ├── wrk2.txt
│       │   ├── server-metrics.csv
│       │   └── threads.txt
│       └── rep2..rep5/
└── E3/, E7/, ...
```

### Report layout

`results/report.md` has three sections:

1. Result matrix (every row, three transports per scenario).
2. Expected vs observed per hypothesis (advisory verdict).
3. Take-aways (one line per question from §Questions).

### Cleanup

```bash
docker compose down -v
rm -rf results/raw
```

### Common failures

| Symptom | Cause | Where to look |
|---|---|---|
| `[bench] FATAL: server never answered` | Build error, port conflict, JKS path wrong | `docker compose logs server` |
| wrk2 reports `cannot assign requested address` | Client ephemeral ports exhausted | raise `ulimit -n`; check `net.ipv4.ip_local_port_range` |
| `pinned events > 0` on NIO-Async or Loom | `synchronized` or native pin in request path | `Thread ... pinned` trace in `server.log` |
| `nr_throttled > 0` | Container CPU throttled | re-run with more cores or higher `cpus:` |
