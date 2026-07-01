package com.jun.nioServer;

import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hand-off task: registers a freshly-accepted {@link SocketChannel} with a target
 * {@link IOReactor}. Runs on a worker thread (not the {@link NioAcceptor} thread) so
 * the SSL handshake performed inside {@link Connection}'s constructor never stalls
 * the accept loop.
 *
 * <p>The handshake runs the channel in blocking mode (see {@link Connection}), so a
 * stalled peer would otherwise pin this worker thread indefinitely — blocking channel
 * reads ignore {@code SO_TIMEOUT}. To bound it, a watchdog scheduled on
 * {@code handshakeWatchdog} closes the channel after {@code handshakeTimeoutMs}; the
 * blocked read then fails with {@code AsynchronousCloseException} and the connection is
 * dropped. The watchdog is cancelled as soon as registration completes.
 */
class ConnectionRegistrar implements Runnable {

    private static final Logger log = Logger.getLogger(ConnectionRegistrar.class);

    private final SocketChannel socketChannel;
    private final IOReactor targetReactor;
    private final SSLContext sslContext;
    private final int socketId;
    private final ScheduledExecutorService handshakeWatchdog;
    private final int handshakeTimeoutMs;

    ConnectionRegistrar(SocketChannel socketChannel, IOReactor targetReactor,
                        SSLContext sslContext, int socketId,
                        ScheduledExecutorService handshakeWatchdog, int handshakeTimeoutMs) {
        this.socketChannel = socketChannel;
        this.targetReactor = targetReactor;
        this.sslContext = sslContext;
        this.socketId = socketId;
        this.handshakeWatchdog = handshakeWatchdog;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    @Override
    public void run() {
        if (socketChannel == null) {
            log.warn("ConnectionRegistrar run with null socketChannel.");
            return;
        }
        if (targetReactor == null) {
            log.error("ConnectionRegistrar run with null targetReactor for socketId: " + socketId);
            closeQuietly();
            return;
        }
        ScheduledFuture<Void> watchdog = armWatchdog();
        try {
            // createConnection runs the SSL handshake on THIS (registrar pool) thread, off
            // the reactor. register() then marshals the selector registration to the reactor.
            Connection connection = targetReactor.createConnection(socketChannel, socketId, sslContext);
            targetReactor.register(connection);
        } catch (Exception e) {
            if (isHandshakeAbortedByPeer(e)) {
                // Peer closed before completing the SSL handshake — port scan, health
                // check, or a plain TCP probe hitting an SSL server. Not an error.
                log.debug("SSL handshake aborted by peer (socketId " + socketId + "): " + e.getMessage());
            } else {
                log.error("Failed to register new socket with IOReactor for socketId: " + socketId
                                  + ". Connection will be dropped.", e);
            }
            closeQuietly();
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
    }

    private boolean isHandshakeAbortedByPeer(Throwable e) {
        String msg = e.getMessage();
        return e instanceof IllegalStateException
                && msg != null
                && msg.contains("end of stream during SSL handshake");
    }

    /** Schedules the handshake-deadline channel close, or returns null when no watchdog applies. */
    private ScheduledFuture<Void> armWatchdog() {
        if (sslContext == null || handshakeWatchdog == null || handshakeTimeoutMs <= 0) {
            return null;
        }
        return handshakeWatchdog.schedule(() -> {
            if (socketChannel.isOpen()) {
                log.warn("SSL handshake timed out after " + handshakeTimeoutMs
                                 + "ms for socketId: " + socketId + "; closing.");
                closeQuietly();
            }
            return null;
        }, handshakeTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void closeQuietly() {
        try {
            if (socketChannel.isOpen()) {
                socketChannel.close();
            }
        } catch (IOException e) {
            log.error("Failed to close socketChannel for socketId: " + socketId, e);
        }
    }
}
