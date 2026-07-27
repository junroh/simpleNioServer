# 0005 — Inline event-loop transport with selective offload

- **Status:** Proposed
- **Supersedes:** —
- **Superseded by:** —
- **Amends:** [0001](0001-seda-nio-testbed.md) — continues [0004](0004-handoff-boundaries.md)'s hand-off reduction and questions the SEDA shape itself for the non-blocking, cheap path
- **Related:** [0001](0001-seda-nio-testbed.md), [0003](0003-task-queue-reactor.md), [0004](0004-handoff-boundaries.md), `docs/THREADING_MODEL.md`, `docs/SSL_HANDSHAKE.md`, `docs/BENCHMARK.md`

## Context

How we got here, in order:

- [0001](0001-seda-nio-testbed.md) built the NIO transport as a SEDA pipeline (stage-per-thread,
  joined by queues) for per-stage *visibility*, not throughput.
- [0004](0004-handoff-boundaries.md) found the hand-offs cost more than they buy: Tier 0 merged
  the reader/writer pools; Tier 1 (proposed) absorbs the dispatcher.
- We removed the obvious per-request allocations, then asked whether allocation is even the
  throughput ceiling. A back-of-the-envelope model says no.

**The ceiling is hand-offs, not garbage.** On the benchmark envelope (`cpus=2`, 256m heap,
`GET /hello`, keep-alive, no pipelining): 500k req/s on 2 cores needs `4 µs of CPU per request`.
The current path spends ~2–4 µs on syscalls, ~3–8 µs on parse/encode, and — dominant — **3–4
thread hand-offs** (`reactor → I/O pool → dispatcher → reactor → I/O pool`), each a context
switch plus cache/TLB migration, ~6–20 µs. So ~15–40 µs/request ≈ 50–130k req/s on 2 cores; GC
is a single-digit-% effect. (These are order-of-magnitude estimates, not measurements.)

**Hand-off cost is a fixed per-request tax**, so it is ruinous where the work is cheap and
negligible where it is expensive:

| Workload | per-request cost | hand-off share | verdict |
| --- | --- | --- | --- |
| non-blocking, cheap (`/hello`) | ~15–40 µs | ~half | hand-offs dominate → inline wins |
| blocking (`/sleep`, DB) | ~ms | ~0% | must offload anyway (a block stalls the loop) |
| heavy/variable CPU | large | small | a shared pool load-balances better than a static partition |

Two clarifications that shape the decision:

- **"Single loop" ≠ single thread.** It is N event loops, one per core, each owning a partition
  of connections and running read → parse → handle → write *inline* — parallelism by
  **connection**, not by **stage**. This keeps a request's data cache-hot on one core and
  amortises `select()` wakeups across the ready batch, instead of migrating each request across
  cores and paying a hand-off per stage.
- **SSL has two separate costs.** The *handshake wait* is multi-round-trip **I/O-bound** (once per
  connection); `docs/SSL_HANDSHAKE.md` already ranks the options and picks model 3 — an
  event-driven state machine *inside the reactor* holding no thread (ceiling: memory). The
  *steady-state crypto* (`wrap`/`unwrap`) is **CPU per record**, cheap for small messages
  (sub-µs–µs, inline is fine) and worth offloading only for large payloads. So "SSL ⇒ keep a
  pool" is too coarse.

Crucially, `docs/SSL_HANDSHAKE.md` model 3 *already prescribes this hybrid*: drive the state
machine inline in the reactor, and offload only the heavy delegated step (`NEED_TASK` →
`ForkJoinPool`), then wake the reactor. This ADR generalises that pattern from the handshake to
the steady state.

## Decision

Adopt one rule — **inline on the reactor by default; offload only the expensive step** — and keep
the pool as that offload executor, not the default path. The SEDA transport stays available for
observability and as a baseline.

Route each step by a cheap, workload-agnostic test (no per-workload benchmark needed to *choose*
inline vs offload — the operation decides):

| Axis | inline on the reactor | offload to the pool |
| --- | --- | --- |
| **type** | plaintext copy, non-blocking parse | TLS crypto (large), **blocking handler** |
| **size** | small payload | payload over a threshold |
| **load** | loop is keeping up | overflow when the loop falls behind (restores work-conservation) |

Foundation and prerequisites:

- **Reactor per core** (`nio.num.io.reactors` = cores), connections partitioned across reactors —
  this is how the inline model uses all cores; without it, inlining concentrates I/O on one core.
- **`maxBytesPerWrite`** (added) bounds an inline write turn, so a large response is chunked
  across OP_WRITE events and never monopolises the loop — the write analogue of `maxBytesPerRead`.
- **SSL**: implement `docs/SSL_HANDSHAKE.md` model 3 (event-driven handshake in the reactor,
  `NEED_TASK` offloaded), and route steady-state crypto by size. This reuses the same connection
  state machine the steady-state inline path needs, so the two land on one mechanism.

Empirical validation (benchmark deltas, threshold tuning) is deferred; the direction rests on the
first-principles model above and the model-3 precedent, and is additive (below) so it can be
compared later without risk.

## Consequences

Positive:

- Removes the dominant per-request cost (hand-off context switches + cache migration) on the
  cheap path — the only change the model expects to move throughput by a multiple.
- Cache-hot processing; share-nothing per-core loops scale near-linearly with cores.
- The pool is retained for genuinely heavy work (large TLS crypto, blocking handlers, overflow),
  so TLS / blocking / skewed workloads do not regress — each lands on the structure that fits it.
- Handshake (model 3) and steady state share one state machine, so building one advances the other.

Negative:

- **Complexity**: a fast-path/slow-path split, routing thresholds, and per-connection ordering
  care (inline and offloaded work on the same connection must not reorder — the existing
  read/write try-locks serialise, and the reactor must `try`-lock + bounce rather than block).
- **Loses the per-stage observability** that was [0001](0001-seda-nio-testbed.md)'s goal, so the
  SEDA path stays for study.
- Reactor-per-core statically partitions connections; a skewed load can imbalance cores —
  mitigated by the load-based overflow-to-pool, which restores work-conservation.
- The async SSL handshake state machine is harder to debug than blocking straight-line code
  (see `docs/SSL_HANDSHAKE.md`).

Adversarial risks and how the design answers them:

| Risk | Answer |
| --- | --- |
| R1: TLS crypto funnels onto the reactor | route crypto by size — large offloaded; handshake uses model 3 (wait holds no thread) |
| R2: inlining concentrates I/O on one core | reactor-per-core + connection partition is the foundation, not optional |
| R3: static partition loses work-conservation | load-based overflow-to-pool when a loop falls behind |
| R4: the gain is unquantified / load-dependent | additive mode; the SEDA/pool path stays as the comparison baseline |

What this ADR does not do:

- It does not remove the SEDA transport or the pool; the pool becomes the offload executor.
- It does not adopt HTTP pipelining or HTTP/2 (orthogonal syscall-amortisation levers, not
  real-traffic throughput fixes).
- It does not fix routing thresholds; they start as defaults and can be tuned later.
