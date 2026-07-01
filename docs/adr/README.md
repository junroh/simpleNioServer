# Architecture Decision Records

Each ADR records one decision at the point it was made. ADRs are immutable: a later
decision that changes an earlier one gets a new number and references the old one
(supersedes / amends) rather than editing it. Some early ADRs here were recorded
retroactively — where so, the ADR says it and dates the decision honestly.

How the docs fit together:

- **ADRs** — *why* the design is the way it is, over time (the timeline).
- `docs/THREADING_MODEL.md` — *how* the server works now (the current cross-section).
- `docs/BENCHMARK.md` — *how the open questions are measured* (the evidence).
- `docs/SSL_HANDSHAKE.md` — the SSL handshake's interaction with the NIO model.

## Index

| # | Title | Status |
| --- | --- | --- |
| [0001](0001-seda-nio-testbed.md) | A SEDA-style NIO server as a C10K testbed | Accepted (retroactive) |
| [0002](0002-virtual-thread-transport.md) | Add a virtual-thread transport to test whether Loom obsoletes NIO | Accepted |
| [0003](0003-task-queue-reactor.md) | Single-threaded selector via a reactor task queue | Accepted |
| [0004](0004-handoff-boundaries.md) | Restrict hand-offs to blocking and parallelism boundaries | Proposed |
