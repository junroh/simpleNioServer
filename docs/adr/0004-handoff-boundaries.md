# 0004 — Restrict hand-offs to blocking and parallelism boundaries

- **Status:** Accepted (Tier 0 implemented; Tier 1 deferred)
- **Supersedes:** —
- **Superseded by:** —
- **Amends:** [0001](0001-seda-nio-testbed.md) — refines the SEDA pipeline; does not discard it
- **Related:** [0001](0001-seda-nio-testbed.md), [0002](0002-virtual-thread-transport.md), [0003](0003-task-queue-reactor.md), `docs/THREADING_MODEL.md`, `docs/BENCHMARK.md`

## Context

[0001](0001-seda-nio-testbed.md) built the NIO server as a SEDA pipeline: many
stages, each on its own thread or pool, connected by queues. That shape was chosen
for a testbed, where per-stage visibility is worth the overhead. [0001](0001-seda-nio-testbed.md)
also noted the cost: every hand-off adds a queue operation, a thread wake-up, and
lost cache locality, and the single-thread MessageDispatcher serializes work per
reactor.

Now that the testbed works, those costs are worth revisiting. A hand-off is only
worth its cost if it buys one of two things:

- a blocking boundary — the next step blocks, so it must run off a
  latency-critical thread (a selector or dispatcher shared by many connections), or
- a parallelism boundary — the work is CPU-heavy and should spread across cores.

A hand-off that buys neither is pure overhead: it pays the queue, wake-up, and
cache cost to move work that was cheap and non-blocking to begin with.

Applying that test to the pipeline's hand-offs:

| Hand-off | What it buys | Verdict |
| --- | --- | --- |
| accept → registrar pool (SSL handshake) | blocking boundary (TLS round-trips block) | keep |
| reactor → reader/writer pools | parallelism (notably SSL encrypt/decrypt) | keep, but one I/O pool, not two |
| reader → dispatcher | neither: parsing is cheap and non-blocking, and the dispatcher is one thread, so it funnels rather than parallelizes | weak |
| dispatcher → async handler pool | blocking boundary (app handler may block) | keep |

Two findings:

- The split reader and writer pools do not buy isolation worth having here. Both
  do short, non-blocking, bounded work. Splitting them trades work-conservation
  (any idle thread can take any job) for an isolation that is not needed, and the
  fixed pools' unbounded queues let one side back up while the other sits idle.
- The reader → dispatcher hand-off is the weakest. The dispatcher is one thread
  per reactor, so it does not parallelize; it serializes. Per-connection ordering
  is already guaranteed by the connection's read lock, not by the dispatcher. Its
  single thread is itself a bottleneck, which is why the NIO design added an async
  handler pool (`AsyncMessageProcessor`) to work around it.

## Decision

Adopt one rule: a hand-off exists only at a blocking boundary or a parallelism
boundary. Apply it in two tiers.

Tier 0 — merge the reader and writer pools (this change):

- Replace the per-reactor reader pool and writer pool with one I/O pool per
  reactor, sized by `nio.num.io.threads` (default 4).
- Do not keep the split as a configuration option. The finding above is that the
  split buys no isolation worth having here, so a `nio.io.pool.split` toggle would
  be a speculative knob for a workload this testbed does not have — the merged
  pool is strictly better on every workload measured. If an asymmetric workload
  ever needs it, git history has the split wiring.
- This removes two sizing knobs (`nio.num.reader.threads` / `nio.num.writer.threads`),
  leaving one, and lets any I/O worker take any read or write job.

Tier 0 is now implemented: one I/O pool per reactor (`nio.num.io.threads`,
default 4). The `nio.num.reader.threads` / `nio.num.writer.threads` knobs are gone.

Tier 1 — absorb the dispatcher (later, gated on measurement):

- Parse on the I/O worker that just read the bytes (cache-hot), instead of handing
  off to a separate dispatcher thread.
- This removes the funnel and the extra hop. It also changes the benchmark's
  "sync dispatcher inline vs async wrapper" comparison, so it is not done until the
  benchmark backs it.

Unchanged: the registrar pool (SSL handshake) and the async handler pool. Both sit
at real blocking boundaries.

## Consequences

Positive:

- Fewer hand-offs, so less queue, wake-up, and cache-miss overhead on the I/O path.
- One I/O pool is work-conserving and drops two knobs (reader/writer sizes) to one.
- The design keeps a clear rule for where hand-offs belong, which is easier to
  reason about than "a stage per concern."

Negative:

- Less per-stage visibility than the original SEDA shape. Merging pools and
  (later) absorbing the dispatcher makes the pipeline less granular, which is a
  small loss for the testbed's original observability goal.
- Tier 1 changes a benchmark baseline, so the two tiers must land in order, with
  measurement between them.

What this ADR does not do:

- It does not keep a split-pool option. The split is removed outright (git history
  preserves the wiring); reintroducing it would need its own ADR and a workload
  that justifies it.
- It does not commit Tier 1. Tier 1 is described here for direction but stays out
  until the benchmark supports it.
