# 0003 — Single-threaded selector via a reactor task queue

- **Status:** Accepted
- **Supersedes:** —
- **Superseded by:** —
- **Amends:** [0001](0001-seda-nio-testbed.md) — changes how the reactor stage handles interest ops; the SEDA stage structure is unchanged
- **Related:** [0001](0001-seda-nio-testbed.md), [0004](0004-handoff-boundaries.md), `docs/THREADING_MODEL.md`

This decision was made on 2026-06-27 while verifying the current NIO code with the
new integration tests. A deterministic hang surfaced; fixing it correctly required
a change to the reactor's threading model, recorded here.

## Context

Integration tests (`src/test/java/com/jun/integration/*IT.java`) exposed responses
that hang until the client times out — most reliably on single-shot requests
(HTTP/1.0 GET, 404), nondeterministically elsewhere.

Root cause: the reactor blocked in an unbounded `selector.select()`, while
**multiple non-reactor threads mutated `SelectionKey` interest ops directly** and
signalled the reactor with `selector.wakeup()`:

- a reader pool thread re-armed `OP_READ` after a read,
- the dispatcher thread armed `OP_WRITE` to send a response,
- a writer pool thread re-armed `OP_WRITE` / closed.

`wakeup()` is an edge-triggered, stateless signal. A wakeup delivered while the
reactor is between `select()` calls is coalesced or consumed by the wrong
iteration, so a just-armed interest op is never serviced. For a single-shot
request there is no later client event to wake the loop again, so the connection
hangs forever.

The original code masked this with `selector.select(1)` (1 ms busy-poll, which
re-scans interest ops regardless of wakeups). A refactor to unbounded `select()`
removed that safety net and exposed the latent race. Two point-fixes attempted
during this session — making `selectionKey` volatile, and adding `wakeup()` to
every interest-op arming — did not fix it; the second made failures more frequent,
because wakeup-based cross-thread signalling cannot be made race-free.

## Decision

Adopt the standard Netty `EventLoop` model: **only the reactor thread ever touches
the `Selector` or any `SelectionKey`.**

Other threads never mutate interest ops directly. They submit a `Runnable` to the
reactor via `IOReactor.execute()`, which enqueues to a
`ConcurrentLinkedQueue` then calls `wakeup()`. The reactor
loop drains the queue every iteration in `runPendingTasks()`
— the only place interest ops, registrations, and key cancels are applied:

```
while (!interrupted) {
    selector.select();      // unbounded block is now correct
    runPendingTasks();      // drain queue — only place keys are touched
    processSelectedKeys();
}
```

This is race-free because the **queue**, not the wakeup, carries the state: even if
wakeups coalesce, the reactor runs every queued task when it wakes. A wakeup only
guarantees the loop runs again.

Concretely:
- `Connection.requestWrite()` / `requestRead()` submit `execute(() -> armOps(...))`;
  the interest-op change runs on the reactor thread.
- Channel registration is a task: `IOReactor.register()`
  runs `channel.register(selector, OP_READ, connection)` on the reactor. The SSL
  handshake stays off the reactor — `createConnection()`
  builds the `Connection` (and runs the blocking handshake) on the registrar pool
  thread before registration.
- `Connection.close()` marshals the key `cancel()` + `attach(null)` to the reactor;
  the `closed` CAS and channel close stay on the calling thread.
- `interestOpsLock` and the reactor's `selectorLock` empty-critical-section trick
  are removed — there is no longer concurrent key access to guard.

### Considered and rejected

- **`select(timeout)` polling** (restore `select(1)` / use `select(50)`): hides the
  race rather than removing it; the reactor wakes on a timer even when idle, which
  contradicts an event-driven design and this project's purpose (a testbed showing
  correct NIO).
- **`wakeup()` on every arming**: tried and observed to fail. Edge-triggered
  signalling across threads is fundamentally lossy; adding more wakeups widened the
  race instead of closing it.

## Consequences

- The lost-wakeup hang is eliminated at the root, deterministically.
- The selector and keys are single-threaded — the `interestOpsLock` and the
  registration lock disappear, simplifying `Connection` and `IOReactor`.
- `Connection` now holds a back-reference to its `IOReactor` (constructor-injected),
  mirroring Netty's `Channel`→`EventLoop` link.
- The reactor remains a single thread per reactor; the task queue is an internal
  mechanism, not a new SEDA stage, so [0001](0001-seda-nio-testbed.md)'s pipeline
  diagram is unchanged.

Scope kept minimal (a learning testbed, not Netty): one queue, `execute()`,
`runPendingTasks()`. No scheduled tasks, priorities, or generic EventLoop
abstraction.
