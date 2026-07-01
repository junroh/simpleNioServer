# 0002 — Add a virtual-thread transport to test whether Loom obsoletes NIO

- **Status:** Accepted
- **Supersedes:** —
- **Superseded by:** —
- **Amends:** does not revise [0001](0001-seda-nio-testbed.md); runs alongside it as an added comparison
- **Related:** [0001](0001-seda-nio-testbed.md), `docs/THREADING_MODEL.md`, `docs/BENCHMARK.md`

## Context

The testbed was idle for several years after its 2020 start (first commits
2020-09-30; work resumed 2025-06). The current work is to finish and clean up the
original NIO design from [0001](0001-seda-nio-testbed.md).

During that work, a new option came up that did not exist in 2020: Project Loom.
JDK 21 (2023-09) shipped virtual threads. A parked virtual thread is cheap. It
costs heap, not an OS thread. That removes the reason thread-per-connection fails
at C10K (see [0001](0001-seda-nio-testbed.md), context question 1).

This adds a third question, after the two in [0001](0001-seda-nio-testbed.md):
can Loom replace NIO? If a parked virtual thread is nearly free, does the simpler
thread-per-connection model (one virtual thread per connection, plain blocking
`read()`/`write()`, no selectors, no hand-offs) match NIO under the same C10K load?

This is a comparison added to the experiment, not a replacement for the NIO
design. The NIO work in [0001](0001-seda-nio-testbed.md) is still the main line.
Loom is a second transport placed next to it to answer question 3.

## Decision

Add a virtual-thread transport next to the existing transports. It uses the same
application contract, so the comparison is fair.

`ThreadedServer` already implements thread-per-connection on a fixed
platform-thread pool, which is the model that C10K breaks. Make its executor
swappable so the same code runs on either:

- a fixed platform-thread pool — the classic thread-per-connection model, which
  reproduces the C10K failure, or
- a virtual-thread-per-task executor (`Executors.newVirtualThreadPerTaskExecutor()`,
  JDK 21+) — the Loom model.

Pick the transport at startup with `server.type` (`threaded` | `virtual` | `nio`).
`SSLSocket extends Socket`, so the worker code is the same for both executors. The
only difference between the threaded and virtual runs is the executor, which is
what a clean comparison needs.

This gives a three-way line-up for the benchmark (see `docs/BENCHMARK.md`):
Threaded, NIO-Async, and Loom Threaded — the C10K failure case, the NIO answer
(with the handler moved off the dispatcher), and the Loom answer. Plain NIO
(`nio.async.dispatch.enabled=false`) is left out: the code supports it, but its
single-thread dispatcher is a known bad default that adds no insight.

## Consequences

Positive:

- The testbed can answer all three questions on one workload: reproduce C10K
  (Threaded), test the NIO fix (NIO-Async), and test Loom (Loom Threaded).
- Little new code, and a clean comparison: the Loom transport reuses the
  threaded code path, so any difference comes from the executor, not the I/O code.

Negative / open:

- The comparison is only fair while the two transports differ only in their
  executor. Keep this true as the code changes.
- It adds a third `server.type` to maintain across the refactor.

What this ADR does not decide:

- It does not conclude whether Loom replaces NIO. That is a question for the
  benchmark, not an architecture decision.
- It does not change the NIO server's structure. Whether the SEDA pipeline's
  hand-offs are still worth their cost is a separate question for a future ADR,
  to be decided by measurement.
