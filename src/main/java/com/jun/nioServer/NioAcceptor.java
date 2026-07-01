package com.jun.nioServer;

import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Single-threaded connection acceptor. Accepts on the {@link ServerSocketChannel} and
 * hands each connection off to a round-robin-selected {@link IOReactor} via a
 * {@link ConnectionRegistrar} on {@code connectionRegistrarExecutor}.
 *
 * <p>The hand-off is asynchronous so the SSL handshake (if any) does not block this
 * thread. The acceptor can run in blocking mode ({@code accept()} blocks) or non-blocking
 * mode (a dedicated {@link Selector} on {@code OP_ACCEPT}).
 */
public class NioAcceptor implements Acceptor {

    private static final Logger log = Logger.getLogger(NioAcceptor.class);

    private final ServerSocketChannel serverSocketChannel;
    private final ExecutorService connectionRegistrarExecutor;
    private final IOReactor[] ioReactors;
    private final SSLContext sslContext;
    private final boolean isNonBlockingAcceptor;
    private final boolean clientSocketBlocking;
    private final String localAddressString;
    private final ScheduledExecutorService handshakeWatchdog;
    private final int sslHandshakeTimeoutMs;

    private Selector selector;
    private volatile boolean running = true;
    /** Counts down once the accept loop is actually running. Callers awaiting
     *  start completion (e.g. integration tests, health probes) wait on this. */
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private int nextReactorIndex = 0;
    private final AtomicInteger socketIdCounter = new AtomicInteger(0);

    public NioAcceptor(ServerSocketChannel serverSocketChannel,
                       ExecutorService connectionRegistrarExecutor,
                       IOReactor[] ioReactors,
                       SSLContext sslContext,
                       boolean isNonBlockingAcceptor,
                       boolean clientSocketBlocking,
                       ScheduledExecutorService handshakeWatchdog,
                       int sslHandshakeTimeoutMs) throws IOException {
        if (serverSocketChannel == null) {
            throw new IllegalArgumentException("ServerSocketChannel cannot be null.");
        }
        if (connectionRegistrarExecutor == null) {
            throw new IllegalArgumentException("ExecutorService for ConnectionRegistrar cannot be null.");
        }
        if (ioReactors == null || ioReactors.length == 0) {
            throw new IllegalArgumentException("IOReactors array cannot be null or empty.");
        }
        this.serverSocketChannel = serverSocketChannel;
        this.connectionRegistrarExecutor = connectionRegistrarExecutor;
        this.ioReactors = ioReactors;
        this.sslContext = sslContext;
        this.isNonBlockingAcceptor = isNonBlockingAcceptor;
        this.clientSocketBlocking = clientSocketBlocking;
        this.handshakeWatchdog = handshakeWatchdog;
        this.sslHandshakeTimeoutMs = sslHandshakeTimeoutMs;

        if (isNonBlockingAcceptor) {
            this.serverSocketChannel.configureBlocking(false);
            this.selector = Selector.open();
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        }
        this.localAddressString = serverSocketChannel.getLocalAddress().toString();
        log.info(String.format("NioAcceptor initialized for %s. accept mode: %s",
                               localAddressString,
                               isNonBlockingAcceptor ? "non-blocking (selector-driven)" : "blocking"));
    }

    @Override
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return readyLatch.await(timeout, unit);
    }

    @Override
    public void stop() {
        log.info("Stopping NioAcceptor for " + localAddressString);
        running = false;
        if (selector != null && selector.isOpen()) {
            selector.wakeup();
            try {
                selector.close();
            } catch (IOException e) {
                log.error("IOException while closing selector in NioAcceptor for " + localAddressString, e);
            }
        }
    }

    @Override
    public void run() {
        log.info("NioAcceptor started for " + localAddressString);
        readyLatch.countDown();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                SocketChannel clientSocket = acceptNext();
                if (clientSocket != null) {
                    dispatch(clientSocket);
                }
            } catch (ClosedByInterruptException e) {
                log.warn("NioAcceptor interrupted on " + localAddressString + ". Shutting down.");
                running = false;
            } catch (AsynchronousCloseException e) {
                log.warn("ServerSocketChannel closed asynchronously on " + localAddressString + ". Shutting down.");
                running = false;
            } catch (ClosedChannelException e) {
                log.warn("Channel closed on " + localAddressString + ". Shutting down.");
                running = false;
            } catch (IOException e) {
                if (!running || (selector != null && !selector.isOpen()) || !serverSocketChannel.isOpen()) {
                    log.warn("NioAcceptor for " + localAddressString + " shutting down: " + e.getMessage());
                } else {
                    log.error("IOException in NioAcceptor run loop for " + localAddressString, e);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Unexpected exception in NioAcceptor run loop for " + localAddressString, e);
                }
            }
        }
        log.info("NioAcceptor thread finished for " + localAddressString);
    }

    private SocketChannel acceptNext() throws IOException {
        if (isNonBlockingAcceptor) {
            return acceptNextNonblocking();
        } else {
            return acceptNextBlocking();
        }
    }

    private SocketChannel acceptNextNonblocking() throws IOException {
        int selectedCount = selector.select();
        if (Thread.currentThread().isInterrupted() || !running) {
            running = false;
            return null;
        }
        if (selectedCount == 0) {
            return null;
        }
        SocketChannel accepted = null;
        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        for (SelectionKey key : selectedKeys) {
            if (key.isValid() && key.isAcceptable()) {
                accepted = serverSocketChannel.accept();
                if (accepted != null) {
                    break;
                }
            }
        }
        selectedKeys.clear();
        return accepted;
    }

    private SocketChannel acceptNextBlocking() throws IOException {
        SocketChannel accepted = serverSocketChannel.accept();
        if (!Thread.currentThread().isInterrupted()) {
            return accepted;
        }
        running = false;
        if (accepted != null) {
            try {
                accepted.close();
            } catch (IOException e) {
                log.error("Error closing client socket after interrupt", e);
            }
        }
        return null;
    }

    private void dispatch(SocketChannel clientSocket) throws IOException {
        clientSocket.configureBlocking(clientSocketBlocking);
        int nextSocketId = socketIdCounter.getAndIncrement();
        IOReactor targetIoReactor = ioReactors[nextReactorIndex];
        nextReactorIndex = (nextReactorIndex + 1) % ioReactors.length;
        try {
            connectionRegistrarExecutor.submit(
                    new ConnectionRegistrar(clientSocket, targetIoReactor, sslContext, nextSocketId,
                                            handshakeWatchdog, sslHandshakeTimeoutMs));
        } catch (RejectedExecutionException e) {
            // Registrar pool + queue are full: shed load by dropping this connection rather
            // than letting backpressure stall the accept loop or exhaust resources.
            log.warn("Connection registrar saturated; rejecting socketId " + nextSocketId + ".");
            try {
                clientSocket.close();
            } catch (IOException ioe) {
                log.error("Error closing rejected client socket " + nextSocketId, ioe);
            }
        }
    }
}
