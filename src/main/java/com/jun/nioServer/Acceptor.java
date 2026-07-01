package com.jun.nioServer;

import java.util.concurrent.TimeUnit;

public interface Acceptor extends Runnable {
    void stop();

    /**
     * Block until the accept loop is actually running, or the timeout expires.
     * Returns true if the acceptor became ready within the timeout.
     */
    boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException;
}
