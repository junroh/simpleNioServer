package com.jun.nioServer;

public interface IAcceptor extends Runnable {
    /**
     * Signals the acceptor to stop its execution loop and clean up resources.
     */
    void stopThread();
}
