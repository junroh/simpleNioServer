package com.jun.nioServer;

public interface IAcceptor extends Runnable {
    /**
     * Signals the acceptor to stop its execution loop and clean up resources.
     */
    void stopThread();

    // Consider adding a method like `getBindAddress()` or `getPort()` if NIOHttpService
    // needs to query this from the acceptor. For now, keeping it minimal.
}
