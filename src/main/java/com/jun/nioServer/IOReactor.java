package com.jun.nioServer;

import com.jun.nioServer.io.IoCallback;
import com.jun.nioServer.io.SocketReader;
import com.jun.nioServer.io.SocketWriter;
import com.jun.nioServer.message.MessageDispatcher;
import com.jun.nioServer.message.MessageReaderFactory;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;

/**
 * Sub-reactor in the multi-stage Reactor pattern. Owns one {@link Selector}; the selector
 * loop runs on the {@link Thread} that {@link NioServerService} creates for it.
 * Application work runs in {@link MessageDispatcher}, never on the selector thread.
 *
 * <p>Threading model (Netty {@code EventLoop} style): <b>only the reactor thread ever
 * touches the {@link Selector} or any {@link SelectionKey}.</b> Other threads (the I/O
 * pool, dispatcher) never mutate interest ops directly; they submit a task via
 * {@link #execute(Runnable)}, which the reactor drains once per loop in
 * {@link #runPendingTasks()}. The task queue—not {@code wakeup()}—carries the state, so a
 * coalesced or "early" wakeup can never drop an interest-op change: when the reactor wakes,
 * it runs every queued task.
 */
public class IOReactor implements Runnable {

    private static final Logger log = Logger.getLogger(IOReactor.class);

    private final Selector selector;
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();
    private final MessageDispatcher messageDispatcher;
    private final MessageReaderFactory readerFactory;
    private final ExecutorService ioPool;
    private final int maxBytesPerRead;
    private final int maxBytesPerWrite;

    public IOReactor(Selector selector,
                     MessageDispatcher messageDispatcher,
                     MessageReaderFactory readerFactory,
                     ExecutorService ioPool,
                     int maxBytesPerRead,
                     int maxBytesPerWrite) {
        if (maxBytesPerRead <= 0) {
            throw new IllegalArgumentException("maxBytesPerRead must be positive");
        }
        if (maxBytesPerWrite <= 0) {
            throw new IllegalArgumentException("maxBytesPerWrite must be positive");
        }
        this.selector = Objects.requireNonNull(selector, "selector");
        this.messageDispatcher = Objects.requireNonNull(messageDispatcher, "messageDispatcher");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
        this.ioPool = Objects.requireNonNull(ioPool, "ioPool");
        this.maxBytesPerRead = maxBytesPerRead;
        this.maxBytesPerWrite = maxBytesPerWrite;
    }

    /** Grace + force window for I/O pool shutdown. I/O threads may be parked
     *  in {@code SocketChannel.read()} on a dead client; if graceful shutdown does not
     *  empty the pool, {@code shutdownNow()} interrupts them (read closes the channel
     *  with {@code ClosedByInterruptException}). Without this, those non-daemon threads
     *  outlive the server and leak across tests / restarts. */
    private static final long IO_POOL_GRACE_TIMEOUT_S = 2L;
    private static final long IO_POOL_FORCE_TIMEOUT_S = 2L;

    public void stop() {
        ExecutorShutdown.shutdown(ioPool, "ioPool", IO_POOL_GRACE_TIMEOUT_S, IO_POOL_FORCE_TIMEOUT_S);
    }

    /**
     * Submit a task to run on the reactor thread. Used by other threads to mutate interest
     * ops / register / cancel keys without touching the selector themselves. The task is
     * enqueued first (state), then the selector is woken (nudge) — so even if the wakeup is
     * coalesced or lands between {@code select()} calls, the task is still drained.
     */
    public void execute(Runnable task) {
        taskQueue.offer(task);
        try {
            selector.wakeup();
        } catch (ClosedSelectorException e) {
            // Shutdown race: the selector closed between enqueue and wakeup. The reactor is
            // gone, so the task will not run — harmless, the channel was already closed by
            // the caller (see Connection.close()). isOpen()+wakeup would be the same TOCTOU.
        }
    }

    /** Drain and run every queued task. Runs on the reactor thread; the only place interest
     *  ops / registrations / key cancels are applied. One failing task must not kill the loop. */
    private void runPendingTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (ClosedSelectorException e) {
                throw e;
            } catch (Exception e) {
                log.error("Reactor task failed", e);
            }
        }
    }

    @Override
    public void run() {
        log.info("IOReactor started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                selector.select();
                runPendingTasks();
                Set<SelectionKey> selected = selector.selectedKeys();
                for (SelectionKey key : selected) {
                    Connection conn = (Connection) key.attachment();
                    if (conn == null) {
                        // Attachment detached by a concurrent Connection.close(); nothing to do.
                        continue;
                    }
                    boolean serviced = false;
                    // isValid() guards both branches: a worker thread's Connection.close()
                    // closes the channel (cancelling the key) concurrently with this loop.
                    if (key.isValid() && key.isReadable()) {
                        onRead(conn);
                        serviced = true;
                    }
                    if (key.isValid() && key.isWritable()) {
                        onWrite(conn);
                        serviced = true;
                    }
                    if (!serviced && key.isValid()) {
                        log.error("Unknown key " +
                                (key.readyOps() & ~(SelectionKey.OP_READ | SelectionKey.OP_WRITE)));
                    }
                }
                selected.clear();
            } catch (ClosedSelectorException e) {
                log.info("Selector closed; IOReactor exiting.");
                return;
            } catch (IOException e) {
                log.error("IoException " + e, e);
            } catch (Exception e) {
                log.error("Unknown Exception " + e, e);
            }
        }
        log.info("IOReactor stopped");
    }

    /**
     * Build a {@link Connection} for a freshly accepted socket. Runs on the caller's
     * (registrar pool) thread, NOT the reactor — the SSL handshake in the constructor must
     * stay off the reactor. Call {@link #register(Connection)} afterwards to wire it in.
     */
    public Connection createConnection(SocketChannel channel, int socketId, SSLContext sslContext)
            throws IOException {
        return new Connection(socketId, channel, readerFactory.createMessageReader(), sslContext, this);
    }

    /**
     * Register an already-built {@link Connection} with this reactor's selector. Marshalled
     * onto the reactor thread so only it touches the selector; {@code register(...,OP_READ,
     * connection)} sets interest and attachment atomically, so no half-registered key is
     * ever observed.
     */
    public void register(Connection connection) {
        execute(() -> {
            try {
                SelectionKey key = connection.getSocketChannel()
                        .register(selector, SelectionKey.OP_READ, connection);
                connection.configKey(key);
                // SSL handshake over-read: bytes the handshake already consumed off the
                // socket won't trigger an OP_READ, so dispatch them now. configKey ran first,
                // so selectionKey is published before any resulting requestWrite.
                if (!connection.isClosed() && connection.hasPendingReadData()) {
                    messageDispatcher.enqueue(connection);
                }
            } catch (ClosedChannelException e) {
                log.warn("Register failed for socketid " + connection.getSocketId()
                        + "; closing. " + e);
                connection.close();
            }
        });
    }

    private void onRead(Connection conn) {
        conn.clearInterestOps(SelectionKey.OP_READ);
        IoCallback callback = new IoCallback() {
            @Override
            public void onComplete(int bytesProcessed, List<ByteBuffer> chunks) {
                if (bytesProcessed > 0) {
                    for (ByteBuffer data : chunks) {
                        conn.addSocketReadData(data);
                    }
                }
                // Enqueue a dispatcher pass when there are bytes to parse, OR when the peer
                // half-closed (EOF) so the dispatcher can close the connection once any
                // in-flight writes drain (see SocketReader / MessageDispatcher).
                if (bytesProcessed > 0 || conn.isCloseAfterWritesRequested()) {
                    messageDispatcher.enqueue(conn);
                }
            }

            @Override
            public void onException(Exception e) {
                if (e instanceof ClosedChannelException) {
                    // Normal flow: client closed the socket after receiving the response.
                    // Not a real error; log at debug so it doesn't pollute the console.
                    if (log.isDebugEnabled()) {
                        log.debug("client closed socketid " + conn.getSocketId());
                    }
                } else {
                    log.warn("Error on reading " + e + " on socketid " + conn.getSocketId() + "; closing.");
                }
                conn.close();
            }
        };
        ioPool.submit(new SocketReader(conn, callback, maxBytesPerRead));
    }

    private void onWrite(Connection conn) {
        conn.clearInterestOps(SelectionKey.OP_WRITE);
        IoCallback callback = new IoCallback() {
            @Override
            public void onComplete(int bytesProcessed, List<ByteBuffer> chunks) {
                // No follow-up needed; SocketWriter re-arms OP_WRITE itself when needed.
            }

            @Override
            public void onException(Exception e) {
                log.warn("Error on writing " + e + " on socketid " + conn.getSocketId() + "; closing.");
                conn.close();
            }
        };
        ioPool.submit(new SocketWriter(conn, callback, maxBytesPerWrite));
    }
}
