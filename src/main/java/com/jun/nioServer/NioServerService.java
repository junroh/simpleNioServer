package com.jun.nioServer;

import com.jun.app.DefaultRouter;
import com.jun.config.ServerConfig;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRouter;
import com.jun.nioServer.http.HttpNioMessageHandler;
import com.jun.nioServer.http.HttpNioMessageReaderFactory;
import com.jun.nioServer.message.AsyncMessageProcessor;
import com.jun.nioServer.message.MessageDispatcher;
import com.jun.nioServer.message.MessageProcessor;
import com.jun.nioServer.message.MessageReaderFactory;
import com.jun.ssl.SslContextFactory;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Composition root for the NIO server. Builds, starts, and shuts down every component of
 * the multi-stage Reactor pipeline:
 *
 * <pre>
 *   NioAcceptor (1 thread) ─accept→ ConnectionRegistrar (executor) ─register→ IOReactor[i]
 *   IOReactor[i] (1 thread/reactor) ─OP_READ→  SocketReader  (I/O pool)
 *                                   ─OP_WRITE→ SocketWriter  (I/O pool)
 *                                            ─parse→ MessageDispatcher (1/reactor)
 *                                                  ─dispatch→ MessageProcessor → response
 * </pre>
 *
 * <p>Each reactor has one work-conserving I/O pool that serves both reads and writes
 * (ADR 0004): any idle worker takes whichever read or write is ready.
 *
 * <p>Protocol selection happens at construction time. The generic constructor takes a
 * {@link MessageReaderFactory} (bytes → Messages) and a {@link MessageProcessor}
 * (Message → response). HTTP-convenience constructors delegate to it.
 */
public class NioServerService {

    protected static final Logger log = Logger.getLogger(NioServerService.class);

    private static final long REACTOR_JOIN_TIMEOUT_MS       = 2_000L;
    private static final long ACCEPTOR_GRACE_TIMEOUT_MS     = 5_000L;
    private static final long ACCEPTOR_FORCE_TIMEOUT_MS     = 1_000L;
    private static final long EXECUTOR_AWAIT_TIMEOUT_S      = 5L;
    private static final long ASYNC_WRAPPER_DRAIN_TIMEOUT_S = 5L;

    /** Per-reactor state bundle. All fields are non-null once a reactor has been initialised. */
    private record ReactorRunner(IOReactor reactor,
                                 Selector selector,
                                 Thread reactorThread,
                                 MessageDispatcher dispatcher,
                                 Thread dispatcherThread) {}

    private final NioServerSettings settings;
    private final MessageReaderFactory readerFactory;
    private final MessageProcessor messageProcessor;

    private SSLContext sslContext;
    private List<ReactorRunner> reactors = Collections.emptyList();
    private ExecutorService connectionRegistrarExecutorService;
    private ScheduledExecutorService handshakeWatchdog;
    private ServerSocketChannel serverSocketChannel;
    private Acceptor acceptor;
    private Thread acceptorThread;

    /**
     * Generic constructor — pick any protocol. Implement your own
     * {@link MessageReaderFactory} and {@link MessageProcessor}, and the rest of the
     * NIO pipeline runs them on every accepted connection.
     */
    public NioServerService(NioServerSettings settings,
                            MessageReaderFactory readerFactory,
                            MessageProcessor messageProcessor) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.readerFactory = Objects.requireNonNull(readerFactory, "readerFactory");
        this.messageProcessor = Objects.requireNonNull(messageProcessor, "messageProcessor");
    }

    /** HTTP convenience — production wiring with the default route table. */
    public NioServerService(ServerConfig config) {
        this(NioServerSettings.fromServerConfig(config),
                new HttpNioMessageReaderFactory(),
                buildHttpProcessor(config));
    }

    /** HTTP convenience — empty router (every path → 404). Useful for tests. */
    public NioServerService(NioServerSettings settings) {
        this(settings, new HttpRouter());
    }

    /** HTTP convenience — custom {@link HttpRequestHandler}. */
    public NioServerService(NioServerSettings settings, HttpRequestHandler appHandler) {
        this(settings, new HttpNioMessageReaderFactory(),
                new HttpNioMessageHandler(Objects.requireNonNull(appHandler, "appHandler")));
    }

    public void start() throws Exception {
        if (acceptorThread != null && acceptorThread.isAlive()) {
            log.warn("Service acceptor thread is already running.");
            return;
        }

        initSslContext();
        if (!initReactors()) {
            throw new RuntimeException("IOReactor creation failed.");
        }
        connectionRegistrarExecutorService = buildRegistrarExecutor();
        if (settings.sslEnabled()) {
            handshakeWatchdog = Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "SslHandshakeWatchdog");
                        t.setDaemon(true);
                        return t;
                    });
        }
        if (!openServerSocket()) {
            shutdownAll();
            throw new RuntimeException("ServerSocketChannel creation failed.");
        }
        initAcceptor();
        startAcceptorThread();
    }

    public void waitStop() throws InterruptedException {
        if (acceptorThread != null) {
            acceptorThread.join();
        }
    }

    public void stop() {
        stopAcceptor();
        shutdownAll();
    }

    /**
     * Returns the OS-assigned local port if the channel is open, or empty before
     * {@link #start()} has bound the channel / after it has been closed. Useful for
     * ephemeral-port tests.
     */
    public OptionalInt getBoundPort() {
        ServerSocketChannel ch = serverSocketChannel;
        if (ch == null || !ch.isOpen()) {
            return OptionalInt.empty();
        }
        try {
            SocketAddress addr = ch.getLocalAddress();
            if (addr instanceof InetSocketAddress isa) {
                return OptionalInt.of(isa.getPort());
            }
        } catch (IOException e) {
            log.warn("getBoundPort() failed: " + e.getMessage());
        }
        return OptionalInt.empty();
    }

    private void initSslContext() throws Exception {
        if (!settings.sslEnabled()) {
            sslContext = null;
            return;
        }
        try {
            sslContext = SslContextFactory.build(
                    settings.keystorePath(),
                    settings.keystorePassword(),
                    settings.keyPassword(),
                    settings.truststorePath(),
                    settings.truststorePassword());
        } catch (Exception e) {
            log.error("Failed to initialize SSLContext for NIO server.", e);
            throw e;
        }
    }

    /** Builds and starts every reactor. Returns false (rolling back partial starts) on failure. */
    private boolean initReactors() {
        int n = settings.numIoReactors();
        List<ReactorRunner> built = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                built.add(buildAndStartReactor(i));
            } catch (IOException e) {
                log.error("Failed to initialize IOReactor " + i + ".", e);
                for (ReactorRunner r : built) {
                    stopReactor(r);
                }
                return false;
            }
        }
        reactors = built;
        return true;
    }

    private ReactorRunner buildAndStartReactor(int idx) throws IOException {
        Selector selector = Selector.open();
        // ADR 0004: one work-conserving I/O pool per reactor — any worker takes any read or write.
        ExecutorService ioPool = Executors.newFixedThreadPool(
                settings.numIoThreads(), daemonFactory("IO-" + idx));

        MessageDispatcher dispatcher = new MessageDispatcher(messageProcessor);
        Thread dispatcherThread = new Thread(dispatcher, "MessageDispatcher-" + idx);
        dispatcherThread.setDaemon(false);
        dispatcherThread.start();

        IOReactor reactor = new IOReactor(selector, dispatcher, readerFactory,
                ioPool, settings.maxBytesPerRead(), settings.maxBytesPerWrite());
        Thread reactorThread = new Thread(reactor, "IOReactor-" + idx);
        reactorThread.setDaemon(false);
        reactorThread.start();

        return new ReactorRunner(reactor, selector, reactorThread, dispatcher, dispatcherThread);
    }

    private boolean openServerSocket() {
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(
                    new InetSocketAddress(settings.hostAddress(), settings.port()),
                    settings.acceptorBacklog());
            return true;
        } catch (IOException e) {
            log.error("Failed to create/bind ServerSocketChannel.", e);
            serverSocketChannel = null;
            return false;
        }
    }

    /**
     * Bounded pool for {@link ConnectionRegistrar} tasks. Each task may run a blocking SSL
     * handshake, so the pool size caps concurrent in-flight handshakes and the queue absorbs
     * short bursts. When both are full, {@code submit()} throws {@link java.util.concurrent.RejectedExecutionException}
     * and {@link NioAcceptor} sheds the connection — preferable to unbounded thread growth.
     */
    private ExecutorService buildRegistrarExecutor() {
        return new ThreadPoolExecutor(
                settings.registrarPoolSize(),
                settings.registrarPoolSize(),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.registrarQueueCapacity()),
                daemonFactory("ConnRegistrar"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void initAcceptor() throws IOException {
        IOReactor[] ioReactors = reactors.stream()
                .map(ReactorRunner::reactor)
                .toArray(IOReactor[]::new);
        acceptor = new NioAcceptor(
                serverSocketChannel,
                connectionRegistrarExecutorService,
                ioReactors,
                sslContext,
                settings.acceptorNonBlocking(),
                settings.clientSocketBlocking(),
                handshakeWatchdog,
                settings.sslHandshakeTimeoutMs());
    }

    /** Starts the acceptor thread and waits for the accept loop to be actually running. */
    private void startAcceptorThread() throws InterruptedException {
        acceptorThread = new Thread(acceptor, acceptor.getClass().getSimpleName() + "-ServiceThread");
        acceptorThread.setDaemon(false);
        acceptorThread.start();
        if (!acceptor.awaitReady(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Acceptor did not become ready within 5 seconds");
        }
    }

    private void stopAcceptor() {
        if (acceptor != null) {
            acceptor.stop();
        }
        if (acceptorThread == null || !acceptorThread.isAlive()) {
            return;
        }
        try {
            acceptorThread.join(ACCEPTOR_GRACE_TIMEOUT_MS);
            if (!acceptorThread.isAlive()) {
                return;
            }
            log.warn("Acceptor thread did not stop gracefully; interrupting.");
            acceptorThread.interrupt();
            acceptorThread.join(ACCEPTOR_FORCE_TIMEOUT_MS);
            if (acceptorThread.isAlive()) {
                log.error("Acceptor thread failed to stop even after interrupt.");
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for acceptor thread to stop.", e);
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownAll() {
        shutdownExecutor(connectionRegistrarExecutorService, "connectionRegistrar");
        shutdownExecutor(handshakeWatchdog, "sslHandshakeWatchdog");
        for (ReactorRunner r : reactors) {
            stopReactor(r);
        }
        // After all dispatchers have stopped feeding it, drain in-flight handler tasks.
        if (messageProcessor instanceof AsyncMessageProcessor amp) {
            amp.shutdown(ASYNC_WRAPPER_DRAIN_TIMEOUT_S, TimeUnit.SECONDS);
        }
        closeServerSocketChannel();
    }

    /** Stops one reactor's components in order: the select thread first (so it can no longer
     *  submit to the I/O pools), then the selector, then the pools it fed, then the dispatcher. */
    private void stopReactor(ReactorRunner r) {
        // Wake the blocking select() and stop the reactor thread before closing the pools it
        // submits reads/writes to — otherwise it submits to a dead pool (RejectedExecutionException).
        if (r.selector().isOpen()) {
            r.selector().wakeup();
        }
        joinInterrupting(r.reactorThread());
        if (r.selector().isOpen()) {
            try {
                r.selector().close();
            } catch (IOException ioe) {
                log.error("Error closing selector", ioe);
            }
        }
        r.reactor().stop();
        joinInterrupting(r.dispatcherThread());
    }

    private void closeServerSocketChannel() {
        if (serverSocketChannel == null || !serverSocketChannel.isOpen()) {
            return;
        }
        try {
            serverSocketChannel.close();
        } catch (IOException e) {
            log.error("Error closing ServerSocketChannel.", e);
        }
    }

    /**
     * Builds the HTTP message processor, optionally wrapping it in
     * {@link AsyncMessageProcessor} so the dispatcher thread is never blocked by the
     * application handler. See {@code docs/BENCHMARK.md} E3 (H3).
     */
    private static MessageProcessor buildHttpProcessor(ServerConfig config) {
        MessageProcessor base = new HttpNioMessageHandler(DefaultRouter.fromConfig(config));
        if (!config.isNioAsyncDispatchEnabled()) {
            return base;
        }
        ExecutorService executor = buildAsyncDispatchExecutor(config.getNioAsyncDispatchExecutor());
        log.info("NIO async dispatch enabled (executor=" + config.getNioAsyncDispatchExecutor() + ")");
        return new AsyncMessageProcessor(base, executor);
    }

    /** Parses {@code nio.async.dispatch.executor}: "virtual" or "fixed:&lt;N&gt;". */
    private static ExecutorService buildAsyncDispatchExecutor(String spec) {
        if (spec == null) {
            throw new IllegalArgumentException("async dispatch executor spec must not be null");
        }
        String s = spec.trim().toLowerCase();
        if (s.equals("virtual")) {
            return AsyncMessageProcessor.newVirtualThreadExecutor();
        }
        if (s.startsWith("fixed:")) {
            try {
                return AsyncMessageProcessor.newFixedHandlerPool(
                        Integer.parseInt(s.substring("fixed:".length()).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid fixed-pool size in '" + spec + "'", e);
            }
        }
        throw new IllegalArgumentException(
                "unknown async dispatch executor spec '" + spec + "' (expected 'virtual' or 'fixed:<N>')");
    }

    private static void shutdownExecutor(ExecutorService exec, String name) {
        ExecutorShutdown.shutdown(exec, name, EXECUTOR_AWAIT_TIMEOUT_S, EXECUTOR_AWAIT_TIMEOUT_S);
    }

    /** Daemon thread factory — server-owned I/O threads must not outlive the JVM, even
     *  if our explicit shutdown path leaks (e.g. a reader stuck in a kernel read on a
     *  half-open peer that ignores TCP RST). */
    private static java.util.concurrent.ThreadFactory daemonFactory(String namePrefix) {
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, namePrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void joinInterrupting(Thread t) {
        t.interrupt();
        try {
            t.join(REACTOR_JOIN_TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
