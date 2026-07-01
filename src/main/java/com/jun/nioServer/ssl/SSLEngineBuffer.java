package com.jun.nioServer.ssl;

import com.jun.nioServer.Connection;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * Per-connection NIO adapter over a JDK {@link SSLEngine}: drives the blocking handshake and
 * provides record-aware {@link #wrap} / {@link #unwrap} for the steady state. A persistent
 * net-in buffer reassembles TLS records split across reads.
 */
public class SSLEngineBuffer {

    private static final Logger log = Logger.getLogger(SSLEngineBuffer.class);

    private final SSLEngine sslEngine;
    private final Connection connection;
    private final int minAppBufferSize;
    private final int minNetBufferSize;

    /** The {@link SSLEngine} is stateful and not safe for concurrent use; this serializes all
     *  of its access ({@link #wrap}, {@link #unwrap}, {@link #close}) for this connection. */
    private final Object engineLock = new Object();

    // Handshake-time scratch buffers.
    private ByteBuffer netInBufferHandshake;
    private ByteBuffer netOutBufferHandshake;
    private ByteBuffer appInBufferHandshake;
    private final ByteBuffer appOutBufferHandshake;

    // Steady-state persistent net-in buffer; always in write mode between calls.
    private ByteBuffer netInBuffer;

    /** Set once {@link #unwrap} sees the peer's close_notify, so the connection can treat a
     *  graceful TLS shutdown like a TCP FIN. */
    private volatile boolean peerClosed;

    public SSLEngineBuffer(SSLContext sslContext, Connection connection) {
        this(serverEngine(sslContext), connection);
    }

    /** Direct-engine constructor used by tests to inject a pre-handshaken engine. */
    public SSLEngineBuffer(SSLEngine engine, Connection connection) {
        this.sslEngine = engine;
        this.connection = connection;

        SSLSession session = sslEngine.getSession();
        this.minNetBufferSize = session.getPacketBufferSize();
        this.minAppBufferSize = session.getApplicationBufferSize();
        // Don't invalidate a session that's already been used (e.g. handshake-injected).
        if (sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
                && session.getCipherSuite().equals("SSL_NULL_WITH_NULL_NULL")) {
            session.invalidate();
        }

        this.netInBufferHandshake = ByteBuffer.allocate(minNetBufferSize);
        this.netOutBufferHandshake = ByteBuffer.allocate(minNetBufferSize);
        this.netOutBufferHandshake.flip();

        this.appInBufferHandshake = ByteBuffer.allocate(minAppBufferSize);
        this.appOutBufferHandshake = ByteBuffer.allocate(minAppBufferSize);
        this.appOutBufferHandshake.flip();

        this.netInBuffer = ByteBuffer.allocate(minNetBufferSize);
    }

    public boolean init() throws IOException {
        sslEngine.beginHandshake();
        try {
            doHandshake();
        } catch (IOException e) {
            log.error("SSL handshake failed for socket " + connection.getSocketId() + ": " + e.getMessage());
            close();
            return false;
        }
        return true;
    }

    public void close() {
        synchronized (engineLock) {
            sslEngine.closeOutbound();
            try {
                doHandshake();
            } catch (Exception ignore) {
                // SSLEngine.close() may surface an "already closed" state; expected.
            }
        }
    }

    /**
     * Network bytes the blocking handshake read past the final handshake record — the peer's
     * first application data arriving coalesced with its Finished. These are already off the
     * socket (no OP_READ will fire), so the caller must feed them back into {@link #unwrap} or
     * the request is lost and the peer hangs. Empty if none.
     */
    public ByteBuffer drainHandshakeOverread() {
        synchronized (engineLock) {
            netInBufferHandshake.flip();
            ByteBuffer leftover = ByteBuffer.allocate(netInBufferHandshake.remaining());
            leftover.put(netInBufferHandshake).flip();
            netInBufferHandshake.clear();
            return leftover;
        }
    }

    public boolean isPeerClosed() {
        return peerClosed;
    }

    /** Decrypts incoming TLS bytes. Buffers partial records across calls. */
    public List<ByteBuffer> unwrap(ByteBuffer freshNet) throws SSLException {
        synchronized (engineLock) {
            netInBuffer = ensureRoomFor(netInBuffer, freshNet.remaining());
            netInBuffer.put(freshNet);
            netInBuffer.flip();
            List<ByteBuffer> out = new LinkedList<>();
            try {
                while (netInBuffer.hasRemaining()) {
                    Step step = unwrapRecord(netInBuffer, ByteBuffer.allocate(minAppBufferSize));
                    switch (step.result().getStatus()) {
                        case OK -> emit(step.buffer(), out);
                        case BUFFER_UNDERFLOW -> { return out; }   // partial tail record; keep buffered
                        case CLOSED -> {                            // close_notify: serve records before it
                            peerClosed = true;
                            return out;
                        }
                        default -> throw new SSLException("Unexpected unwrap status: " + step.result().getStatus());
                    }
                }
                return out;
            } finally {
                netInBuffer.compact();
            }
        }
    }

    /** Encrypts outgoing application bytes into one or more flipped TLS records. */
    public List<ByteBuffer> wrap(ByteBuffer appData) throws SSLException {
        synchronized (engineLock) {
            List<ByteBuffer> out = new LinkedList<>();
            while (appData.hasRemaining()) {
                Step step = wrapRecord(appData, ByteBuffer.allocate(minNetBufferSize));
                switch (step.result().getStatus()) {
                    case OK -> emit(step.buffer(), out);
                    case CLOSED -> throw new SSLException("SSL outbound closed");
                    default -> throw new SSLException("Unexpected wrap status: " + step.result().getStatus());
                }
            }
            return out;
        }
    }

    private static SSLEngine serverEngine(SSLContext ctx) {
        SSLEngine e = ctx.createSSLEngine();
        e.setUseClientMode(false);
        e.setWantClientAuth(false);
        e.setNeedClientAuth(false);
        return e;
    }

    private void doHandshake() throws IOException {
        netOutBufferHandshake.clear();
        netInBufferHandshake.clear();
        SocketChannel socketChannel = connection.getSocketChannel();
        HandshakeStatus status = sslEngine.getHandshakeStatus();
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            status = switch (status) {
                case NEED_UNWRAP -> handshakeUnwrap(socketChannel);
                case NEED_WRAP -> handshakeWrap(socketChannel);
                case NEED_TASK -> runDelegatedTasks();
                default -> throw new IllegalStateException("Invalid SSL status: " + status);
            };
        }
    }

    private HandshakeStatus handshakeUnwrap(SocketChannel socketChannel) throws IOException {
        // Process buffered records before blocking for more: a peer flight that arrived
        // coalesced would otherwise deadlock us in a read waiting for bytes we already hold.
        if (netInBufferHandshake.position() == 0) {
            handshakeRead(socketChannel);
        }
        netInBufferHandshake.flip();
        Step step = unwrapRecord(netInBufferHandshake, appInBufferHandshake);
        appInBufferHandshake = step.buffer();
        netInBufferHandshake.compact();

        switch (step.result().getStatus()) {
            case OK -> { }
            case BUFFER_UNDERFLOW -> {
                netInBufferHandshake = ensureRoomFor(netInBufferHandshake, minNetBufferSize);
                handshakeRead(socketChannel);
            }
            case CLOSED -> {
                if (sslEngine.isOutboundDone()) {
                    throw new IllegalStateException("SSLEngine was already closed");
                }
                sslEngine.closeOutbound();
                return sslEngine.getHandshakeStatus();
            }
            default -> throw new IllegalStateException("Invalid SSL status: " + step.result().getStatus());
        }
        return step.result().getHandshakeStatus();
    }

    private HandshakeStatus handshakeWrap(SocketChannel socketChannel) throws IOException {
        netOutBufferHandshake.clear();
        SSLEngineResult result = sslEngine.wrap(appOutBufferHandshake, netOutBufferHandshake);
        switch (result.getStatus()) {
            case OK -> {
                netOutBufferHandshake.flip();
                flush(socketChannel, netOutBufferHandshake);
            }
            case CLOSED -> {
                // Flush the close_notify; let a socket failure propagate (close() ignores it,
                // init() drops the connection) rather than re-looping on a dead socket.
                netOutBufferHandshake.flip();
                flush(socketChannel, netOutBufferHandshake);
                netInBufferHandshake.clear();
            }
            case BUFFER_OVERFLOW -> netOutBufferHandshake = enlargeBuffer(netOutBufferHandshake, minNetBufferSize);
            default -> throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return result.getHandshakeStatus();
    }

    /** Blocking handshake read; throws if the peer closed its send side mid-handshake. */
    private void handshakeRead(SocketChannel socketChannel) throws IOException {
        if (socketChannel.read(netInBufferHandshake) >= 0) {
            return;
        }
        if (sslEngine.isInboundDone() && sslEngine.isOutboundDone()) {
            throw new IllegalStateException("SSLEngine was already closed");
        }
        try {
            sslEngine.closeInbound();
        } catch (SSLException e) {
            log.debug("Forced to close inbound: " + e.getLocalizedMessage());
        }
        sslEngine.closeOutbound();
        throw new IllegalStateException("Reached end of stream during SSL handshake");
    }

    /** Run delegated tasks (cert validation, key derivation) and read the resulting status. */
    private HandshakeStatus runDelegatedTasks() {
        Runnable task;
        List<Future<?>> futures = new ArrayList<>();
        while ((task = sslEngine.getDelegatedTask()) != null) {
            futures.add(ForkJoinPool.commonPool().submit(task));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.warn("SSL delegated task failed: " + e.getMessage(), e);
            }
        }
        return sslEngine.getHandshakeStatus();
    }

    private Step unwrapRecord(ByteBuffer netIn, ByteBuffer appOut) throws SSLException {
        return oneRecord(sslEngine::unwrap, netIn, appOut, minAppBufferSize);
    }

    private Step wrapRecord(ByteBuffer appIn, ByteBuffer netOut) throws SSLException {
        return oneRecord(sslEngine::wrap, appIn, netOut, minNetBufferSize);
    }

    /**
     * The single call site for {@link SSLEngine#wrap} / {@link SSLEngine#unwrap}: codes one
     * record from {@code src} into {@code dst}, growing {@code dst} and retrying while the
     * engine reports {@code BUFFER_OVERFLOW}. The returned status is never {@code BUFFER_OVERFLOW}.
     */
    private Step oneRecord(EngineCodec codec, ByteBuffer src, ByteBuffer dst, int minDst) throws SSLException {
        int srcPos = src.position();
        while (true) {
            SSLEngineResult result = codec.code(src, dst);
            if (result.getStatus() != Status.BUFFER_OVERFLOW) {
                return new Step(result, dst);
            }
            dst = enlargeBuffer(dst, minDst);
            src.position(srcPos);   // rewind and recode the same record into the bigger buffer
        }
    }

    private void emit(ByteBuffer dst, List<ByteBuffer> out) {
        dst.flip();
        if (dst.hasRemaining()) {
            out.add(dst);
        }
    }

    private void flush(SocketChannel socketChannel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            socketChannel.write(buffer);
        }
    }

    private ByteBuffer enlargeBuffer(ByteBuffer buffer, int minProposed) {
        return ByteBuffer.allocate(Math.max(minProposed, buffer.capacity() * 2));
    }

    /** Ensures the buffer (in write mode) has at least {@code addBytes} of remaining capacity,
     *  reallocating and copying existing contents if needed. */
    private ByteBuffer ensureRoomFor(ByteBuffer buffer, int addBytes) {
        if (buffer.remaining() >= addBytes) {
            return buffer;
        }
        int needed = buffer.position() + addBytes;
        ByteBuffer bigger = ByteBuffer.allocate(Math.max(needed, buffer.capacity() * 2));
        buffer.flip();
        bigger.put(buffer);
        return bigger;
    }

    @FunctionalInterface
    private interface EngineCodec {
        SSLEngineResult code(ByteBuffer src, ByteBuffer dst) throws SSLException;
    }

    /** One engine step plus the destination buffer, which may have been grown to fit the record. */
    private record Step(SSLEngineResult result, ByteBuffer buffer) {}
}
