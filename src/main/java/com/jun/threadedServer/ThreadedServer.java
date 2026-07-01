package com.jun.threadedServer;

import com.jun.config.ServerConfig;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRouter;
import com.jun.ssl.SslContextFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-per-connection server. Each accepted socket is handed to a per-connection
 * {@link Runnable} built by the configured {@link WorkerFactory}.
 *
 * <p>Protocol selection happens at construction time. The generic constructor takes a
 * {@code WorkerFactory}; the HTTP-convenience {@link #fromConfig} factories plug in
 * {@link HttpWorker} as the factory.
 *
 * <p>SSL: a non-null {@link SSLContext} switches the listen socket to
 * {@link javax.net.ssl.SSLServerSocket}. The worker is unchanged because
 * {@code SSLSocket extends Socket}.
 *
 * <p>Executor: when {@code useVirtualThreads} is true, each accepted socket runs on a
 * fresh virtual thread (JDK 21+); otherwise on a fixed platform-thread pool of
 * {@code threadPoolSize}.
 */
public class ThreadedServer implements Runnable {

    private final int serverPort;
    private final int backlog;
    private final WorkerFactory workerFactory;
    private final SSLContext sslContext;

    // Package-private so unit tests in the same package can assert lifecycle state
    // (open/closed, shutdown/terminated) without exposing them on the public API.
    final ExecutorService threadPool;
    ServerSocket serverSocket;

    private Thread server;
    private boolean isStopped;

    /** Canonical constructor — every other entry point delegates here. */
    public ThreadedServer(int port, int backlog, int threadPoolSize,
                          WorkerFactory workerFactory, SSLContext sslContext,
                          boolean useVirtualThreads) {
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.serverPort = port;
        this.backlog = backlog;
        this.threadPool = createExecutor(threadPoolSize, useVirtualThreads);
        this.sslContext = sslContext;
    }

    /** Test convenience — every path returns 404, plaintext, fixed pool. */
    public ThreadedServer(int port) {
        this(port, 1024, 100, socket -> new HttpWorker(socket, new HttpRouter()), null, false);
    }

    public static ThreadedServer fromConfig(ServerConfig cfg, HttpRequestHandler appHandler,
                                            boolean useVirtualThreads) {
        return new ThreadedServer(
                cfg.getServerPort(),
                cfg.getServerBacklog(),
                cfg.getThreadedServerPoolSize(),
                httpFactory(appHandler),
                buildSslContext(cfg),
                useVirtualThreads);
    }

    public synchronized void start() {
        // Bind synchronously so getBoundPort() is meaningful as soon as start() returns.
        openServerSocket();
        server = new Thread(this, getClass().getSimpleName());
        server.start();
    }

    @Override
    public void run() {
        while (!isStopped()) {
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch (IOException e) {
                if (isStopped()) {
                    break;
                }
                throw new RuntimeException("Error accepting client connection", e);
            }
            threadPool.submit(workerFactory.create(clientSocket));
        }
        threadPool.shutdown();
    }

    public void waitStop() throws InterruptedException {
        server.join();
    }

    public synchronized void stop() {
        if (isStopped) {
            return;
        }
        isStopped = true;
        if (server != null) {
            server.interrupt();
        }
        if (serverSocket == null) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            throw new RuntimeException("Error closing server", e);
        }
    }

    public synchronized boolean isStopped() {
        return isStopped;
    }

    /**
     * Returns the actual bound port if the socket is open, empty otherwise. Useful for
     * ephemeral-port tests.
     */
    public OptionalInt getBoundPort() {
        ServerSocket s = serverSocket;
        if (s == null || s.isClosed()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(s.getLocalPort());
    }

    private void openServerSocket() {
        try {
            if (sslContext != null) {
                serverSocket = sslContext.getServerSocketFactory()
                                         .createServerSocket(serverPort, backlog);
            } else {
                serverSocket = new ServerSocket(serverPort, backlog);
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot open port " + serverPort, e);
        }
    }

    private static ExecutorService createExecutor(int poolSize, boolean useVirtual) {
        return useVirtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(poolSize);
    }

    private static WorkerFactory httpFactory(HttpRequestHandler appHandler) {
        Objects.requireNonNull(appHandler, "appHandler");
        return socket -> new HttpWorker(socket, appHandler);
    }

    private static SSLContext buildSslContext(ServerConfig cfg) {
        if (!cfg.isSslEnabled()) {
            return null;
        }
        try {
            return SslContextFactory.build(
                    cfg.getSslKeystorePath(),
                    cfg.getSslKeystorePassword(),
                    cfg.getSslKeyPassword(),
                    cfg.getSslTruststorePath(),
                    cfg.getSslTruststorePassword());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SSLContext for threaded server", e);
        }
    }
}
