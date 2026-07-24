# Threading model

How the NIO server's threads are organised today. For *why* this design,
see the [ADRs](adr/README.md):
[0001](adr/0001-seda-nio-testbed.md) SEDA testbed for C10K,
[0002](adr/0002-virtual-thread-transport.md) Loom transport,
[0003](adr/0003-task-queue-reactor.md) single-threaded selector via task queue,
[0004](adr/0004-handoff-boundaries.md) hand-off boundaries. For measurements,
see `docs/BENCHMARK.md`.

## 1. The problem this design solves

Thread-per-connection servers run out of threads, not CPU. Each open socket
holds one thread blocked in `read()`. At 10 000 keep-alive connections:

- ~1 MB stack per thread → ~10 GB committed RAM
- 10 000 entries in the OS scheduler
- context-switch cost on every wake/sleep

The CPU is mostly idle. The cost is the threads that are waiting.

The fix is to stop spending threads on waiting. One thread asks the OS
"which of these sockets are ready right now?" via a readiness primitive
(`epoll`, `kqueue`), exposed in Java as `Selector` over non-blocking
channels. That one thread is woken when any of the registered sockets
becomes readable or writable.

Thread count then tracks CPU cores and in-flight work — not connection
count. 10 000 idle connections cost 10 000 file descriptors + heap, not
10 000 threads.

This is the only reason the NIO transport exists. The constraint that
follows from it:

> Shared threads (reactor, dispatcher, I/O pool) never block on
> something that hasn't happened yet.

## 2. Two kinds of waiting

Some work intrinsically waits and cannot be made instantaneous:

- **TLS handshake** — multiple round-trips with the peer
- **Application handler** — DB call, downstream HTTP, disk

That waiting cannot be deleted, only assigned to a thread that is allowed
to block. The architecture splits threads into two groups:

**Latency-critical** — shared by many connections. One blocked latency-critical
thread stalls every connection it serves. Reactor selector, dispatcher,
I/O pool.

**Work-absorbing** — owns one connection or one task at a time. One blocked
work-absorbing thread stalls only that task. Registrar pool, async handler
pool.

Blocking work is pushed onto the work-absorbing threads, and those pools
are size-capped so the absorption itself can't grow without bound.

## 3. Thread inventory

`R` = `nio.num.io.reactors`. Defaults from `ServerConfig` in parentheses.

| Group                  | Count                                       | Class                     | If it blocks                                          |
| ---                    | ---                                         | ---                       | ---                                                   |
| Acceptor               | 1                                           | latency-critical          | new connections stop being accepted                   |
| Reactor selector       | `R` (default 1)                             | **latency-critical**      | every connection on that reactor stalls               |
| I/O pool               | `R × nio.num.io.threads` (default 1×4)      | latency-critical¹         | reads/writes for other sockets on that reactor queue  |
| Dispatcher             | `R` (one per reactor)                       | latency-critical          | all parsing/handling on that reactor stops            |
| Registrar pool         | `nio.registrar.pool.size` (default 64)      | **work-absorbing**        | only that one connection's setup waits                |
| Async handler pool     | optional, `fixed:N` (default 500) or virtual| **work-absorbing**        | only that one request waits                           |
| SSL handshake watchdog | 1 (SSL only)                                | helper                    | — (only schedules timeouts)                           |

¹ In practice these never block — the underlying `read()`/`write()` is
non-blocking — but the pool is *shared per reactor*, so blocking work is
kept off it. One pool serves both reads and writes: any idle worker takes
whichever is ready.

## 4. Reactor: the thread that must not block

`IOReactor.run()`:

```
select()                     // park until at least one socket is ready
runPendingTasks()            // run interest-op / register / cancel tasks
                             // other threads enqueued via reactor.execute(...)
for each ready key:
    OP_READ  → ioPool.submit(SocketReader)
    OP_WRITE → ioPool.submit(SocketWriter)
```

Only the reactor thread touches the selector or any `SelectionKey`. Other
threads that need to change interest ops, register, or cancel call
`reactor.execute(task)`; the reactor drains those in `runPendingTasks()`
once per loop. The task queue carries the state — `wakeup()` is only a
nudge — so a coalesced or early wakeup never drops a change. See
[ADR 0003](adr/0003-task-queue-reactor.md).

The reactor never reads or writes bytes, never parses, never runs
application code. Each is submitted to a worker pool. Any of those done
inline would stall every connection on the reactor.

### Why `R > 1`

The select-and-dispatch loop is one thread on one core. Past some
connection count it saturates that core. `R` reactors split the connection
set into `R` independent loops, each with its own selector, I/O pool, and
dispatcher — no shared mutable state, so they scale
without contention. Start with `R = 1`; raise only when one `IOReactor-*`
thread is at ~100% CPU while others/cores sit idle.

## 5. The I/O pool

`SocketReader` / `SocketWriter` run the actual `socketChannel.read()` /
`write()` on the per-reactor I/O pool. One work-conserving pool
serves both reads and writes, so any idle worker takes whichever job is
ready ([ADR 0004](adr/0004-handoff-boundaries.md)). Both are non-blocking — `read()` returns
whatever bytes are available right now (possibly zero) and returns
immediately. The thread does a short burst and goes back to the pool.

Two invariants protect these threads:

- **Fairness via `maxBytesPerRead`.** `SocketReader` caps how much it
  pulls per turn, then re-arms `OP_READ` and returns. One chatty connection
  cannot monopolise a pool thread; it gets another turn next select cycle.
- **One worker per connection at a time.** `Connection.tryReadLock` /
  `tryWriteLock` are try-locks; a second worker handed the same connection
  returns immediately instead of blocking. Concurrency *across* connections,
  serialization *within* one.

After a read, the reader enqueues the connection on the reactor's
`MessageDispatcher` and re-arms `OP_READ`. It does not parse.

## 6. Dispatcher and the async option

Each reactor has one `MessageDispatcher` thread. It drains a queue of
ready connections, runs the per-connection `MessageReader` to turn bytes
into `Message`s, and calls the `MessageProcessor` (the application
handler).

By default the handler runs **inline on the dispatcher thread**. Fine for
fast, CPU-bound handlers. A handler that blocks (DB, downstream HTTP,
disk) blocks the only dispatcher for that reactor; throughput collapses to
`R / handler_latency`. This is the documented NIO weakness — see
`docs/BENCHMARK.md` E3 / H3, H8.

The fix is `AsyncMessageProcessor`, which wraps any `MessageProcessor` and
submits each call to a separate executor. Enable with
`nio.async.dispatch.enabled=true`. The executor is one of:

- **`fixed:N`** (default `fixed:500`) — bounded platform-thread pool;
  hard ceiling on in-flight handlers.
- **`virtual`** — one virtual thread per message; a parked virtual thread
  costs heap, not an OS thread.

Same pattern as the SSL handshake (see `docs/SSL_HANDSHAKE.md`):
necessary blocking is moved off latency-critical threads onto a
work-absorbing pool.

## 7. `Connection` concurrency

A connection is touched by several threads over its life — reader fills
read buffers, dispatcher parses, writer drains write buffers, handler
calls `sendResponse` from yet another thread. The `SelectionKey` and its
interest ops are touched by exactly one — the reactor.

- **Interest ops: reactor thread only.** Any other thread that needs to
  change them submits a task via `reactor.execute(...)`; the reactor
  applies it in `runPendingTasks()`. No lock on the key because no
  concurrent access.
- **`readLock` / `writeLock`** — try-locks that serialize the byte-level
  read/write loops.
- **Byte and message queues** — concurrent collections, safe for the
  multi-producer / single-consumer hand-offs.
- **`closed` flag (CAS)** — makes close idempotent and lets every stage
  cheaply skip a connection that's already gone.
- **`inFlight` counter + the single `maybeClose()` decision** — a graceful
  close (peer half-close, or `Connection: close`) must not fire while a
  response may still be produced. Each queued dispatcher pass and each
  in-flight (possibly async) handler holds an `inFlight` token; `maybeClose()`
  closes only when a close was requested, `inFlight` is zero, and nothing is
  left to write — so the close decision lives in exactly one place and can't
  race a response being assembled.

Two connections never contend. One connection is kept consistent by
confining all key access to the reactor and serializing its byte loops.

## 8. Sizing the pools

Total thread count is a function of CPU cores and pool sizes — not of
open connection count. For `R` reactors with an I/O pool of `io`
threads each, async off, SSL on:

```
1                 acceptor
+ R               reactor selectors
+ R × io          I/O workers
+ R               dispatchers
+ registrar_pool  (bounded; busy only during connection setup / handshake)
+ 1               handshake watchdog
```

`R=4, io=4` is ~25 threads whether the server holds 100 or 100 000
connections. Threads bounded by hardware, connections bounded by memory.

### Knobs

**`nio.num.io.reactors` (R)** — most important. Each reactor is one select
loop pinned to one core. Start at **1**. Raise only when one `IOReactor-*`
thread saturates a core under load. Ceiling: the cores you're willing to
dedicate to I/O dispatch (`cores` or `cores/2`). More than that is idle
select loops.

**`nio.num.io.threads` (io)** — size of the I/O pool per reactor. One
work-conserving pool serves both reads and writes (any idle worker takes
any job — [ADR 0004](adr/0004-handoff-boundaries.md)). Non-blocking,
CPU-bound byte work (copy, and for SSL, encrypt/decrypt). Default **4**.
Increase only if reads/writes visibly queue behind a busy reactor *and*
there are spare cores. Total is `R × io`, so scales with R.

**`nio.registrar.pool.size`** — caps concurrent in-flight handshakes.
Work-absorbing — threads here block during TLS round-trips. Each parked
thread costs ~1 MB stack but ~0 CPU, so it can be much larger than the
core count. Size by Little's law: connection-establishment rate × handshake
latency. E.g. 500 new TLS conns/s × 20 ms ≈ 10 concurrent; the default
**64** is comfortable. Too small → new TLS connections shed on burst; too
large → wasted stacks, weaker back-pressure. Irrelevant when SSL is off.

**`nio.registrar.queue.capacity`** — burst absorber in front of the
registrar pool. Short spikes above `pool.size` wait briefly instead of
being dropped. Pool + queue both full → new connections shed (closed).
Default **256**.

**`nio.ssl.handshake.timeout.ms`** — safety bound, not a sizing knob.
Caps how long any registrar thread can be held by a stalled peer, so a
slowloris cannot permanently consume the registrar pool. Default **10 s**.

**Async handler pool** (`nio.async.dispatch.enabled=true`) — sized to the
*application*, not the I/O layer. Default `fixed:500`. Use a smaller `N`
when the handler hits a resource with its own concurrency ceiling (e.g. a
DB pool of N). Use `virtual` when handlers block on something with no
fixed ceiling and you want effectively unbounded concurrency at heap cost.

Rule of thumb: tune `R` to CPU, leave `r`/`w` small, size the registrar
pool to TLS connection rate, push application blocking into the async
handler pool rather than growing the I/O pools.

## 9. The threaded transport, for contrast

The same application contract also runs as `ThreadedServer`: one thread
per accepted socket, from a fixed pool or (JDK 21+) a
virtual-thread-per-task executor. The handler reads/writes a blocking
`Socket` directly — no hand-offs, no interest ops. With virtual threads,
the cost of a parked thread drops enough that this transport is
competitive on C10K-style workloads. The NIO transport is preferable when
explicit control over the I/O thread budget and the multi-stage pipeline
matters. `docs/BENCHMARK.md` is what quantifies the trade.
