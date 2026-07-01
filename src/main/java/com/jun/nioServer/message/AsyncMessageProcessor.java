package com.jun.nioServer.message;

import com.jun.nioServer.Connection;
import org.apache.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Offloads each {@link #processMessage(Message)} call onto an {@link ExecutorService} so
 * the calling {@link MessageDispatcher} thread is never blocked by the application
 * handler. This removes the dispatcher bottleneck that constrains NIO throughput to
 * {@code numReactors / handler_latency} under blocking handlers — see
 * {@code docs/BENCHMARK.md} E3 and hypothesis H3.
 *
 * <p>Composition over modification: {@link MessageDispatcher} and concrete processors
 * such as {@link com.jun.nioServer.http.HttpNioMessageHandler} are untouched. Wrap any
 * {@code MessageProcessor} to make it async; binary protocols benefit identically.
 *
 * <p>Lifecycle: {@link com.jun.nioServer.NioServerService} owns the wrapper and calls
 * {@link #shutdown(long, TimeUnit)} during its own stop sequence.
 */
public class AsyncMessageProcessor implements MessageProcessor {

    private static final Logger log = Logger.getLogger(AsyncMessageProcessor.class);

    private final MessageProcessor delegate;
    private final ExecutorService executor;

    public AsyncMessageProcessor(MessageProcessor delegate, ExecutorService executor) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void processMessage(Message message) {
        // The response is produced on the executor thread AFTER this returns (and after the
        // dispatcher pass releases its own token). Take an in-flight token spanning the async
        // task so a graceful close cannot fire while the response is being assembled/sent.
        // (In production a Message always has a Connection; guard for connectionless test use.)
        Connection connection = message.getConnection();
        if (connection != null) {
            connection.enterInFlight();
        }
        try {
            executor.submit(() -> {
                try {
                    delegate.processMessage(message);
                } catch (Exception e) {
                    // Mirror the synchronous path (MessageDispatcher): drop the socket so the
                    // peer fails fast instead of hanging for its full request timeout.
                    log.error("delegate failed for message id=" + message.getId() + "; closing", e);
                    if (connection != null) {
                        connection.close();
                    }
                } finally {
                    if (connection != null) {
                        connection.leaveInFlight();
                    }
                }
            });
        } catch (RuntimeException e) {
            // Submit failed (e.g. executor shutting down): the task will not run, so release the
            // token we took above, otherwise the connection could never gracefully close.
            log.error("executor rejected message id=" + message.getId(), e);
            if (connection != null) {
                connection.leaveInFlight();
            }
        }
    }

    /** Shuts down the executor; waits up to {@code timeout} for in-flight tasks to drain. */
    public void shutdown(long timeout, TimeUnit unit) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout, unit)) {
                log.warn("executor did not drain within " + timeout + " " + unit
                        + "; forcing shutdownNow()");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Returns a virtual-thread-per-task executor (JDK 21+). */
    public static ExecutorService newVirtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Returns a fixed-size platform-thread pool. */
    public static ExecutorService newFixedHandlerPool(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        return Executors.newFixedThreadPool(size, r -> {
            Thread t = new Thread(r, "NioAsyncHandler");
            t.setDaemon(false);
            return t;
        });
    }
}
