# SSL Handshake in the NIO Server

## The core tension: handshakes and a non-blocking event loop

The NIO server multiplexes many connections over a few `Selector` threads
(`IOReactor`). For that to work, every socket the reactor watches must be in
**non-blocking** mode and must never make a reactor thread wait — the reactor's
job is to detect readiness and hand work off, then immediately move on.

A TLS handshake fits this model badly. It is a multi-round-trip exchange whose
wall-clock time is dominated by *waiting* for the peer to send the next record.
That waiting has to live somewhere, and where we put it decides how many
connections the server can set up at once — a first-class concern for a
high-load server.

## Where the handshake wait can live

There are three places to absorb the handshake's waiting, with very different
ceilings:

### 1. Spin on a non-blocking channel (rejected)

Drive the `SSLEngine` with a `while` loop directly on the non-blocking channel.
When the peer hasn't sent data yet, `SocketChannel.read()` returns `0`
immediately, so the loop spins.

- **one in-flight handshake ≈ one CPU core at 100%**, doing no useful work
- concurrent handshakes capped at roughly the **core count**
- a stalled peer pins a core forever — no natural timeout

This is the worst option for a high-load server and is not used.

### 2. Block a worker thread, off the reactor (current choice)

Run the handshake on a dedicated worker pool with the channel temporarily in
**blocking** mode, then switch it back to non-blocking before registering it with
a reactor `Selector`.

- a blocking `read()` **parks** the thread instead of spinning — a waiting
  handshake costs ~0 CPU
- concurrent handshakes are bounded by **thread count**, not cores; parked
  threads are cheap, so the pool can be sized well above the core count
- the reactor only ever sees fully-handshaken, non-blocking sockets — the event
  loop stays clean

Trade-offs:

- the ceiling is the worker pool size, and each parked thread carries a ~1 MB
  stack
- blocking `SocketChannel` reads **ignore `SO_TIMEOUT`**, so a stalled peer would
  pin a worker thread indefinitely unless we add our own deadline (see Watchdog)

### 3. Drive the handshake inside the reactor, event-driven (future)

Make the handshake itself non-blocking and run its state machine inside the
reactor select loop, so a waiting handshake holds no thread at all. See
[Future direction](#future-direction).

## How option 2 is wired today

The handshake path spans four classes:

- **`NioAcceptor`** accepts the socket and submits a `ConnectionRegistrar` task
  to a bounded worker pool. If the pool and its queue are both full, the
  connection is **shed** (socket closed) rather than queued unboundedly.
- **`ConnectionRegistrar`** runs on a worker thread and registers the socket with
  a round-robin-chosen `IOReactor`. For SSL connections it arms a watchdog around
  the registration.
- **`Connection`** (constructed during registration) switches the channel to
  blocking mode, runs the handshake via `SSLEngineBuffer`, then restores
  non-blocking in a `finally` before the channel is registered with the selector.
- **`NioServerService`** owns the bounded registrar pool and the shared watchdog
  scheduler, and shuts both down.

### Bounded registrar pool

A `ThreadPoolExecutor` with fixed core/max = `nio.registrar.pool.size` and an
`ArrayBlockingQueue(nio.registrar.queue.capacity)`. The pool size caps concurrent
in-flight handshakes; the queue absorbs short bursts. On saturation `submit()`
throws `RejectedExecutionException` and `NioAcceptor` closes the socket —
load-shedding instead of unbounded thread growth.

### Watchdog (handshake deadline)

Because blocking channel reads ignore `SO_TIMEOUT`, a shared single-thread
`ScheduledExecutorService` closes the channel after
`nio.ssl.handshake.timeout.ms`. The blocked read then fails with
`AsynchronousCloseException` and the connection is dropped. The watchdog is
cancelled as soon as registration completes. This is what defends against
slowloris-style stalled handshakes.

### Settings

| Property | Default | Meaning |
| --- | --- | --- |
| `nio.registrar.pool.size` | 64 | Max concurrent connection registrations / SSL handshakes |
| `nio.registrar.queue.capacity` | 256 | Registrar queue depth before new connections are rejected |
| `nio.ssl.handshake.timeout.ms` | 10000 | Watchdog deadline for one SSL handshake |

### Crossing into the steady state

Two seams between the blocking handshake and the non-blocking read path matter:

- **Handshake over-read.** The blocking handshake read often pulls the peer's first
  application bytes (the HTTP request) in the same TCP segment as its `Finished`. Those
  bytes are already off the socket, so no `OP_READ` will ever fire for them — the
  handshake hands them back and they are fed into the steady-state read pipeline, or the
  request would be lost and the peer would hang.
- **`close_notify` is a half-close.** When the peer's TLS `close_notify` arrives, the
  engine reports it and the connection drives the *same* graceful-close path as a TCP
  FIN: serve whatever already decrypted, then close once pending writes drain. TLS and
  TCP shutdown are handled uniformly rather than as two special cases.

## Future direction

The current design's ceiling is the registrar pool size. To lift it to the
**memory** limit, move the handshake off the worker pool and into the
`IOReactor` select loop — the way Netty does it:

- give `Connection` a state (`HANDSHAKING` → `READY`)
- drive the `SSLEngine` asynchronously instead of with a blocking `while`:
  - `NEED_UNWRAP` → register `OP_READ` and return (no thread held); the selector
    wakes the reactor when the peer sends more bytes
  - `NEED_WRAP` → `wrap` and write; if the socket buffer is full, register
    `OP_WRITE` and return
  - `NEED_TASK` → run delegated tasks on `ForkJoinPool.commonPool()`, then
    `wakeup()` the reactor to resume
  - `FINISHED` → flip to `READY`; subsequent events use the normal read/write path

Payoff: a waiting handshake holds no thread — only an `OP_READ` registration and
a little heap — so concurrent handshakes become bounded by memory rather than
threads or cores. The registrar pool could then shrink or disappear, and the
watchdog becomes a per-connection deadline checked inside the reactor.

Cost: `SSLEngineBuffer.doHandshake()`'s synchronous loop must be rewritten as a
resumable state machine, reactor re-entry after `NEED_TASK` must be coordinated,
and the asynchronous flow is harder to debug than straight-line blocking code.

In short, the handshake-concurrency ceiling rises with each option:

```
spin on non-blocking : ≈ core count          (rejected)
block off-reactor    : ≈ registrar pool size  (current)
event-driven         : ≈ memory limit         (future)
```
