# 0005 — Consolidate the pipeline toward a single-loop transport

- **Status:** Proposed
- **Supersedes:** —
- **Superseded by:** —
- **Amends:** [0001](0001-seda-nio-testbed.md) — continues the direction [0004](0004-handoff-boundaries.md) started (fewer hand-offs), and questions the SEDA shape itself for the throughput goal
- **Related:** [0001](0001-seda-nio-testbed.md), [0003](0003-task-queue-reactor.md), [0004](0004-handoff-boundaries.md), `docs/THREADING_MODEL.md`, `docs/BENCHMARK.md`

## Context

How we got here, in order:

- [0001](0001-seda-nio-testbed.md) built the NIO transport as a SEDA pipeline — many stages,
  each on its own thread/pool, joined by queues — because the testbed's goal was *per-stage
  visibility*, not raw throughput.
- [0004](0004-handoff-boundaries.md) noticed those hand-offs cost more than they buy: Tier 0
  merged the reader/writer pools into one I/O pool; Tier 1 (proposed) would absorb the
  dispatcher so parsing runs on the I/O worker that read the bytes.
- We then removed the obvious per-request allocations (redundant copies, double maps, eager
  log strings). Those were free wins, but they raised the question this ADR answers: **is
  allocation even the throughput ceiling?**

A back-of-the-envelope model says no. On the benchmark envelope (`cpus=2`, 256m heap,
`GET /hello`, keep-alive, no HTTP pipelining):

- **The hard ceiling.** 500k req/s on 2 cores means `2 × 1e6 µs/s ÷ 5e5 req = 4 µs of CPU per
  request`, summed across every thread. That is the budget any design must fit under.
- **Where the current design spends it.** Per request: ~2–4 µs of syscalls (read + write),
  ~3–8 µs of parse/allocate/encode, and — the dominant term — **3–4 thread hand-offs**
  (`reactor → I/O pool → dispatcher → I/O pool`), each a context switch plus a cache/TLB
  migration when the work moves cores, ~6–20 µs total. That is **~15–40 µs/request → roughly
  50–130k req/s on 2 cores.** GC from allocation churn is a single-digit-% effect at this
  altitude, not the ceiling.
- **The lever is the hand-offs, not the garbage.** A consolidated loop (one thread does
  `select → read → parse → handle → write` inline, Netty `EventLoop` style) pays ~0 hand-off
  cost — wakeups amortise across the batch of ready connections per `select()` — and the
  same work runs cache-hot. That is ~6–10 µs/request → **~200–350k req/s on 2 cores, a 2–4×
  jump**, with no change to the allocation profile.
- **Why 500k specifically needs more.** Even a lean single loop cannot beat ~4 µs/request on
  2 cores with two real syscalls per request. 500k appears only with either **(consolidated
  design × ~4 cores)** — share-nothing per-core loops scale near-linearly — or **(2 cores ×
  HTTP pipelining)**, where one `read()` carries many requests and amortises the syscall cost
  (this is how TechEmpower "plaintext" reaches millions: pipelining × many cores, not
  per-request processing power).

These are order-of-magnitude estimates, not measurements. They yield a falsifiable
prediction to check first (see Consequences): ~50–130k req/s and ~3–4 context switches per
request on the current design.

## Decision

Treat **stage consolidation as the primary throughput lever**, ahead of allocation
micro-optimisation, and pursue it in the direction [0004](0004-handoff-boundaries.md) Tier 1
already points:

1. Land [0004](0004-handoff-boundaries.md) Tier 1 (parse on the I/O worker) first — it
   removes one hand-off and is the smallest step that tests the model.
2. If the measured hand-off cost matches the model, continue toward a **single-loop transport
   option**: one event-loop thread per core owns a partition of connections and runs
   read → parse → handle → write inline, with no dispatcher and no I/O-pool hand-off. Keep it
   as a *transport choice* alongside the SEDA path, not a replacement.
3. Keep the async-handler pool ([0004](0004-handoff-boundaries.md), unchanged) as the escape
   for **blocking** handlers: a blocking handler must not run on the shared loop, so the
   single-loop path only applies when the handler is non-blocking (or is dispatched off-loop).

HTTP pipelining / HTTP-2 multiplexing is noted as an **orthogonal** lever (syscall
amortisation) but is not adopted here: HTTP/1.1 pipelining is effectively dead in real
clients, and HTTP/2 is a large protocol effort. It stays a possible later experiment for
headline numbers, not a throughput fix for real traffic.

## Consequences

Positive:

- Removes the dominant per-request cost (hand-off context switches + cache migration), the
  only change the model expects to move throughput by a multiple rather than a few percent.
- Cache-hot processing: the bytes are read, parsed, and answered on one thread/core.
- Share-nothing per-core loops scale near-linearly with cores (partitioned connections, no
  cross-thread queues), so "add cores" finally pays off — the path to 500k+.
- Fewer threads and fewer sizing knobs than the SEDA pipeline.

Negative:

- **Loses the per-stage visibility that was [0001](0001-seda-nio-testbed.md)'s founding
  goal.** A single loop is one thread doing everything; you can no longer watch a stage's
  queue depth in isolation. This is the real cost — the testbed trades its observability for
  throughput, so the SEDA path must stay available for study.
- A blocking handler stalls every connection on its loop, so the single-loop path is only
  safe for non-blocking handlers or must hand blocking work off-loop — which reintroduces a
  hand-off exactly where [0004](0004-handoff-boundaries.md) says it is worth paying for.
- It is a larger rewrite than Tier 1, and it changes the benchmark's transport set (a new
  comparison point), so it must land after Tier 1 with measurement between.
- Pipelining, if ever pursued, inflates benchmark numbers more than real-world throughput;
  documenting it without that caveat would mislead.

What this ADR does not do:

- It does not commit the single-loop rewrite. It commits to the *ordering* (consolidation
  before allocation work) and to Tier 1 as the next concrete step, gated on the measured
  hand-off cost confirming the model (~3–4 context switches/request, ~50–130k req/s on 2
  cores per `docs/BENCHMARK.md`).
- It does not remove the SEDA transport; that stays for observability and as a benchmark
  baseline.
- It does not adopt HTTP pipelining or HTTP/2.
