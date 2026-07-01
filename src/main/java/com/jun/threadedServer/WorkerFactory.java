package com.jun.threadedServer;

import java.net.Socket;

/**
 * Builds a per-connection worker for the threaded server. Plug in any protocol by
 * implementing this — the default {@link HttpWorker} wires HTTP, but a binary protocol
 * can provide its own Runnable that reads/writes the socket's streams.
 */
public interface WorkerFactory {
    Runnable create(Socket clientSocket);
}
