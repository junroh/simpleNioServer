# 0001 — A SEDA-style NIO server as a C10K testbed

- **Status:** Accepted (recorded retroactively in 2025-06; decided at the project's start, 2020-09-30)
- **Supersedes:** —
- **Superseded by:** —
- **Amended by:** [0003](0003-task-queue-reactor.md) — reactor threading model; [0004](0004-handoff-boundaries.md) — refines the pipeline's hand-offs
- **Related:** [0002](0002-virtual-thread-transport.md), [0003](0003-task-queue-reactor.md), [0004](0004-handoff-boundaries.md), `docs/THREADING_MODEL.md`, `docs/BENCHMARK.md`

This ADR was written after the fact. It records the reasoning as it stood in 2020,
without later knowledge (virtual threads did not yet exist). Later ADRs cover what
changed.

## Context

This project is a testbed, not a production server. It exists to answer two
questions by building and running the code:

1. Can I reproduce the C10K problem? That is the case where a
   thread-per-connection server fails under many mostly-idle connections while the
   CPU is almost idle. Each blocked `read()` holds a thread, and a thread costs
   about 1 MB of stack plus a scheduler slot plus context-switch overhead.
2. Does NIO fix it? Does a `Selector`-based, non-blocking design hold up under the
   same load that breaks the thread-per-connection version?

To answer question 2, I need to see where time goes under load, not just a final
throughput number. I want to know which stage is the bottleneck. That need drives
the design.

## Decision

Build the NIO server as a SEDA-style (Staged Event-Driven Architecture) pipeline.
Split request handling into separate stages. Each stage has its own thread or
pool, and stages pass work to each other through queues.

The stages (see `docs/THREADING_MODEL.md` for the current description):

- a single-threaded acceptor
- a connection registrar pool, where the SSL handshake runs, off the accept loop
- one IOReactor per reactor, running the `select()` loop, which passes ready
  sockets to the reader and writer pools
- per-reactor reader and writer pools, which do the non-blocking I/O
- a per-reactor MessageDispatcher, which parses bytes into messages and calls the
  application handler

Each stage is one small class on its own thread. The selector thread never runs
application code.

```
   ServerSocketChannel
           │  accept()
           ▼
   ┌───────────────────────┐
   │ NioAcceptor (1 thread)│   round-robin pick
   └───────────────────────┘
           │  submit ConnectionRegistrar
           ▼
   ┌─────────────────────────┐
   │ ConnectionRegistrar pool│   SSL handshake here, off the accept loop
   └─────────────────────────┘
           │  register channel on selector
           ▼
   ┌─────────────────────────────────────────────────────────┐
   │ IOReactor[i] — 1 thread per reactor                     │
   │   selector.select()                                     │
   │     OP_READ  → submit SocketReader  (readerPool[i])     │
   │     OP_WRITE → submit SocketWriter  (writerPool[i])     │
   └─────────────────────────────────────────────────────────┘
                       │
      bytes readable   │   bytes writable
                       ▼
   ┌──────────────────────────┐         ┌──────────────────────────┐
   │ SocketReader             │         │ SocketWriter             │
   │ (readerPool worker)      │         │ (writerPool worker)      │
   │  reads up to             │         │  drains write buffers,   │
   │  maxBytesPerRead         │         │  re-arms OP_WRITE if     │
   │  then re-arms OP_READ    │         │  the kernel buffer fills │
   └──────────────────────────┘         └──────────────────────────┘
                       │
                       ▼
   ┌────────────────────────────────────────┐
   │ MessageDispatcher (1 per reactor)      │   parse bytes → Message
   └────────────────────────────────────────┘   via per-connection MessageReader
                       │
                       ▼
   ┌────────────────────────────────────────┐
   │ MessageProcessor                       │   build response,
   │   (HttpNioMessageHandler for HTTP)     │   connection.sendResponse(msg)
   └────────────────────────────────────────┘
```

A later ADR may revisit whether every hand-off is worth its cost. This diagram
records the original shape and does not change.

## Why SEDA

- Per-stage visibility. Each stage is a separate thread or pool with a queue in
  front, so a bottleneck shows up in one place: a queue grows, or a thread hits
  100%. The testbed exists to find these limits, so this visibility is the point.
- Independent tuning. Each stage can be sized on its own (reactor count, reader and
  writer pool sizes), which lets me see how each knob affects the system.
- Separation of concerns. Accept, I/O, parse, and application logic are in
  separate places, which makes the non-blocking model easier to follow.
- Blocking stays off the selector. Reads, writes, and parsing run on worker pools,
  so the reactor thread only does `select()` and hand-off. That is what lets one
  thread serve many connections.

## Consequences

Positive:

- The pipeline is instrumented by construction, so C10K behaviour and NIO's
  response can be measured stage by stage. This becomes the basis of
  `docs/BENCHMARK.md`.
- Thread count is bounded by hardware and pool sizes, not by connection count.
  That is the property the experiment is meant to show.

Negative, noted now and to watch:

- Every hand-off has a cost: a queue operation, a thread wake-up (park/unpark plus
  context switch), and lost cache locality when the next stage touches the
  connection's buffers on a different core. A stage that does only cheap,
  non-blocking work may not be worth the hand-off that feeds it.
- The MessageDispatcher is a single thread per reactor, so it serializes all
  parsing and handler work for that reactor. This is a bottleneck if the handler
  is slow.

These costs are accepted for the testbed: stage visibility is worth more than the
per-hop overhead here. Whether the I/O stages stay justified is a question for
measurement, not assumption. See future ADRs.
