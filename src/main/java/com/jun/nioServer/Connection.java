package com.jun.nioServer;

import com.jun.nioServer.io.SocketWriter;
import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageReader;
import com.jun.nioServer.ssl.SSLEngineBuffer;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-connection state for the NIO transport, the meeting point of every NIO stage.
 * Carries:
 * <ul>
 *   <li>Transport state: {@link SocketChannel}, {@link SelectionKey}, optional
 *       {@link SSLEngineBuffer}.</li>
 *   <li>Pipeline buffers: incoming bytes (after SSL decrypt) and outgoing bytes
 *       (before SSL encrypt), plus parsed-message and ready-to-write queues.</li>
 *   <li>Per-connection {@link MessageReader} (set at construction).</li>
 *   <li>Try-lock pattern so only one worker thread reads or writes at a time.</li>
 *   <li>High-level helpers — {@link #sendResponse} and {@link #requestWrite} — so app
 *       code never touches the selector directly.</li>
 * </ul>
 *
 * <p>Interest-op changes never happen on the calling thread: they are marshalled to the
 * reactor via {@link IOReactor#execute(Runnable)} so that only the reactor thread touches
 * the {@link Selector} / {@link SelectionKey}. {@code readLock} / {@code writeLock} still
 * serialize the byte-level read/write loops; the byte/message queues are independently
 * concurrent.
 */
public class Connection {

    private static final Logger log = Logger.getLogger(Connection.class);

    private final int socketId;
    private final SocketChannel socketChannel;
    private final MessageReader messageReader;
    private final IOReactor reactor;
    /**
     * Published once by {@link #configKey} on the reactor thread. Volatile for safe
     * publication to {@link #close()}, which may read it from
     * another thread. All <em>mutation</em> of its interest ops happens on the reactor thread.
     */
    private volatile SelectionKey selectionKey;
    private SSLEngineBuffer sslEngineBuffer;

    private final ReentrantLock readLock = new ReentrantLock();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Set when the peer asked for {@code Connection: close} (or some other protocol-
     *  level signal to terminate). The connection stays alive until {@link SocketWriter}
     *  has drained every queued message + buffer, then closes. */
    private volatile boolean closeAfterWrites;
    /** Outstanding units of work that may still produce a response: each queued dispatcher
     *  pass and each in-flight (possibly async) handler holds a token. A graceful close must
     *  not fire while {@code inFlight > 0}, otherwise it can tear down the SSL engine while a
     *  response is being assembled. See {@link #maybeClose()}. */
    private final AtomicInteger inFlight = new AtomicInteger(0);

    /** Serializes response assembly ({@link #prepareBuffersForWriting}) so two handlers that
     *  call {@link #sendResponse} concurrently (e.g. under async dispatch) cannot interleave
     *  their bytes in {@code writeBuffers} or use the single {@code SSLEngine} concurrently.
     *  Independent of {@code writeLock} so a producer never blocks on the writer's socket I/O. */
    private final Object writeAssemblyLock = new Object();

    private final ConcurrentLinkedDeque<ByteBuffer> readBuffers  = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Message>    readMsgs     = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<ByteBuffer> writeBuffers = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Message>    writeMsgs    = new ConcurrentLinkedDeque<>();

    Connection(int sockId, SocketChannel socket, MessageReader messageReader, IOReactor reactor)
            throws IOException {
        this.socketId = sockId;
        this.socketChannel = socket;
        this.messageReader = Objects.requireNonNull(messageReader, "messageReader");
        this.reactor = Objects.requireNonNull(reactor, "reactor");
        socketChannel.configureBlocking(false);
    }

    Connection(int sockId, SocketChannel socket, MessageReader messageReader,
               SSLContext sslContext, IOReactor reactor)
            throws IOException {
        this(sockId, socket, messageReader, reactor);
        if (sslContext != null) {
            // Run the handshake in blocking mode so the SSLEngine state machine's reads
            // park the thread instead of busy-spinning on a non-blocking channel. Restored
            // to non-blocking before return so the channel can be registered with a Selector.
            // A watchdog (see ConnectionRegistrar) bounds how long this may block.
            socketChannel.configureBlocking(true);
            try {
                sslEngineBuffer = new SSLEngineBuffer(sslContext, this);
                if (!sslEngineBuffer.init()) {
                    throw new IOException("Failed to do SSL handshake");
                }
                // The blocking handshake read can pull the peer's first application bytes
                // (the request) in with its Finished (confirmed under load). Feed any such
                // over-read into the read pipeline — no OP_READ fires for already-consumed
                // bytes, so IOReactor.register dispatches it once registered.
                ByteBuffer overread = sslEngineBuffer.drainHandshakeOverread();
                if (overread.hasRemaining()) {
                    addSocketReadData(overread);
                }
            } finally {
                if (socketChannel.isOpen()) {
                    socketChannel.configureBlocking(false);
                }
            }
        }
    }

    public boolean tryReadLock() {
        return readLock.tryLock();
    }

    public void unlockRead() {
        readLock.unlock();
    }

    public boolean tryWriteLock() {
        return writeLock.tryLock();
    }

    public void unlockWrite() {
        writeLock.unlock();
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public int getSocketId() {
        return socketId;
    }

    public MessageReader getMessageReader() {
        return messageReader;
    }

    /**
     * Publish the SelectionKey. Runs on the reactor thread (from the register task);
     * {@link IOReactor#register} already set OP_READ + attachment atomically, so this only
     * stores the reference — or cancels it if the connection was closed before registering.
     */
    public void configKey(SelectionKey k) {
        if (closed.get()) {
            k.cancel();
            return;
        }
        this.selectionKey = k;
    }

    /**
     * Ask the reactor to arm OP_WRITE. Safe to call from any thread; the actual interest-op
     * change runs on the reactor thread via {@link IOReactor#execute}, so a coalesced wakeup
     * can never drop it.
     */
    public void requestWrite() {
        if (closed.get()) {
            return;
        }
        reactor.execute(() -> armOps(SelectionKey.OP_WRITE));
    }

    /** Ask the reactor to re-arm OP_READ. Safe to call from any thread. */
    public void requestRead() {
        if (closed.get()) {
            return;
        }
        reactor.execute(() -> armOps(SelectionKey.OP_READ));
    }

    /** Add interest ops. Reactor-thread only (invoked from a task submitted via execute). */
    private void armOps(int ops) {
        SelectionKey k = this.selectionKey;
        if (closed.get() || k == null || !k.isValid()) {
            return;
        }
        k.interestOps(k.interestOps() | ops);
    }

    /** Clear interest ops. Reactor-thread only (called inline from onRead / onWrite). */
    public void clearInterestOps(int ops) {
        SelectionKey k = this.selectionKey;
        if (closed.get() || k == null || !k.isValid()) {
            return;
        }
        k.interestOps(k.interestOps() & ~ops);
    }

    public boolean isClosed() {
        return closed.get() || !socketChannel.isConnected() || !socketChannel.isOpen();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (sslEngineBuffer != null) {
                sslEngineBuffer.close();
            }
            // Selector/key access is reactor-thread-only: marshal the cancel. Closing the
            // channel here also implicitly cancels the key (thread-safe per NIO spec); the
            // marshalled cancel + detach keeps the "only reactor touches keys" invariant
            // and clears the attachment so the Connection is not retained.
            SelectionKey k = this.selectionKey;
            if (k != null) {
                reactor.execute(() -> {
                    k.attach(null);
                    k.cancel();
                });
            }
            socketChannel.close();
        } catch (Exception e) {
            log.error("Error on closing socket " + socketId + " due to " + e);
        }
    }

    public void addSocketReadData(ByteBuffer buff) {
        readBuffers.offer(buff);
    }

    /** True if raw socket bytes are queued awaiting decrypt/parse (e.g. SSL handshake
     *  over-read fed in at construction). Lets the reactor kick off the dispatcher for
     *  bytes that arrived before registration, which no OP_READ would surface. */
    public boolean hasPendingReadData() {
        return !readBuffers.isEmpty();
    }

    public List<ByteBuffer> drainSocketReadData() {
        List<ByteBuffer> buffers = new LinkedList<>();
        ByteBuffer buff;
        while ((buff = readBuffers.poll()) != null) {
            if (sslEngineBuffer != null) {
                try {
                    buffers.addAll(sslEngineBuffer.unwrap(buff));
                } catch (Exception e) {
                    log.error("error on unwrapping data socketid=" + socketId + " " + e);
                    return new LinkedList<>();
                }
            } else {
                buffers.add(buff);
            }
        }
        // Unify peer-done detection: a TLS close_notify (unwrap → CLOSED) means the peer
        // half-closed, exactly like a TCP FIN (handled by SocketReader's EOF path). Any
        // records that arrived coalesced with the close are already in `buffers` and will be
        // parsed/served; mark the connection so it closes once those writes drain. Called
        // outside SSLEngineBuffer's engineLock; safe because the dispatcher pass that invoked
        // us still holds an in-flight token, so maybeClose defers until leaveInFlight.
        if (sslEngineBuffer != null && sslEngineBuffer.isPeerClosed()) {
            markCloseAfterWrites();
        }
        return buffers;
    }

    public void addReadReadyMsg(Message msg) {
        readMsgs.add(msg);
    }

    public List<Message> drainReadyMessages() {
        return drainAll(readMsgs);
    }

    public void addWriteReadyMsg(Message msg) {
        writeMsgs.add(msg);
    }

    /** Queue a response message and ask the reactor to write it out. */
    public void sendResponse(Message msg) {
        addWriteReadyMsg(msg);
        if (prepareBuffersForWriting()) {
            requestWrite();
        }
    }

    public boolean prepareBuffersForWriting() {
        // Serialized: a message's chunks must land in writeBuffers contiguously, and the single
        // SSLEngine.wrap must not run on two threads at once.
        synchronized (writeAssemblyLock) {
            Message msg;
            // Peek → wrap locally → publish bytes → only THEN poll the message, so the message
            // is always in writeMsgs or its bytes in writeBuffers — never neither (a both-empty
            // window would let a concurrent close-decider drop the response).
            while ((msg = writeMsgs.peek()) != null) {
                List<ByteBuffer> wrapped = new LinkedList<>();
                for (byte[] chunk : msg.getChunks()) {
                    ByteBuffer bb = ByteBuffer.wrap(chunk);
                    if (sslEngineBuffer != null) {
                        try {
                            wrapped.addAll(sslEngineBuffer.wrap(bb));
                        } catch (Exception e) {
                            // The SSL engine cannot encrypt this response (e.g. its outbound
                            // side is already closed). The stream is unrecoverable, so the
                            // response can never be sent — close deterministically instead of
                            // silently dropping it and leaving the peer to hang.
                            log.error("error on wrapping data socketid=" + socketId
                                    + "; closing. " + e);
                            close();
                            return false;
                        }
                    } else {
                        wrapped.add(bb);
                    }
                }
                writeBuffers.addAll(wrapped);   // publish bytes first
                writeMsgs.poll();               // then drop the message
            }
            return !writeBuffers.isEmpty();
        }
    }

    public List<ByteBuffer> drainWriteBuffers() {
        return drainAll(writeBuffers);
    }

    private static <T> List<T> drainAll(java.util.Queue<T> queue) {
        List<T> out = new LinkedList<>();
        T item;
        while ((item = queue.poll()) != null) {
            out.add(item);
        }
        return out;
    }

    /**
     * Return buffers that {@link SocketWriter} drained but could not fully write (kernel send
     * buffer filled) to the FRONT of the queue, in order, so the next cycle resumes where this
     * one stopped (otherwise large responses arrive truncated). Called by the writer under the
     * write lock, so the front-insert races only with appends of new responses to the back.
     */
    public void returnUnwrittenBuffers(List<ByteBuffer> unwritten) {
        if (unwritten == null || unwritten.isEmpty()) {
            return;
        }
        ListIterator<ByteBuffer> it = unwritten.listIterator(unwritten.size());
        while (it.hasPrevious()) {
            writeBuffers.addFirst(it.previous());
        }
    }

    /**
     * Request graceful shutdown: close once every pending write has drained and no work is
     * in flight. Used by the application handler for {@code Connection: close}.
     */
    public void closeAfterPendingWrites() {
        this.closeAfterWrites = true;
        requestWrite();   // ensure a writer cycle runs if bytes are pending
        maybeClose();
    }

    /**
     * Mark "peer is done sending; close once pending writes drain." Used by
     * {@link SocketReader} on EOF. Only sets the flag (plus a close attempt) — the response
     * to an in-flight request is produced after the reader returns, and {@link #maybeClose()}
     * will not close while {@code inFlight > 0}.
     */
    public void markCloseAfterWrites() {
        this.closeAfterWrites = true;
        maybeClose();
    }

    /** Whether a close has been requested (peer half-closed or {@code Connection: close}). */
    public boolean isCloseAfterWritesRequested() {
        return closeAfterWrites;
    }

    /** Register a unit of work (dispatcher pass / handler) that may still produce a response. */
    public void enterInFlight() {
        inFlight.incrementAndGet();
    }

    /** Release a unit of work; if it was the last, attempt the graceful close. */
    public void leaveInFlight() {
        if (inFlight.decrementAndGet() == 0) {
            maybeClose();
        }
    }

    /**
     * THE single graceful-close decision. Closes only when a close was requested, no work is
     * in flight, and nothing is left to write — guarded by {@code tryWriteLock} so a writer
     * mid-{@code socketChannel.write()} isn't cut off (an empty queue doesn't mean the bytes
     * reached the socket). A writer holding the lock re-runs this after unlocking, so the close
     * is never lost. Idempotent and safe from any thread.
     */
    public void maybeClose() {
        if (!closeAfterWrites || inFlight.get() != 0 || closed.get()) {
            return;
        }
        if (!writeMsgs.isEmpty() || !writeBuffers.isEmpty()) {
            return;
        }
        if (tryWriteLock()) {
            try {
                if (writeMsgs.isEmpty() && writeBuffers.isEmpty()) {
                    close();
                }
            } finally {
                unlockWrite();
            }
        }
        // If a writer holds the lock, it will call maybeClose() after its post-write unlock.
    }
}
