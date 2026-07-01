package com.jun.nioServer;

import org.apache.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Graceful-then-forced shutdown for the server's executors, shared by the reactor pools
 *  ({@link IOReactor}) and the service-level executors ({@link NioServerService}). */
final class ExecutorShutdown {

    private static final Logger log = Logger.getLogger(ExecutorShutdown.class);

    private ExecutorShutdown() {
    }

    /** Stops {@code exec}: waits {@code graceSec} for a clean drain, then {@code shutdownNow()}
     *  and waits {@code forceSec} more. No-op if {@code exec} is null. */
    static void shutdown(ExecutorService exec, String name, long graceSec, long forceSec) {
        if (exec == null) {
            return;
        }
        exec.shutdown();
        try {
            if (exec.awaitTermination(graceSec, TimeUnit.SECONDS)) {
                return;
            }
            log.warn(name + " did not drain in " + graceSec + "s; interrupting in-flight tasks.");
            exec.shutdownNow();
            if (!exec.awaitTermination(forceSec, TimeUnit.SECONDS)) {
                log.error(name + " still has running threads after shutdownNow.");
            }
        } catch (InterruptedException e) {
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
