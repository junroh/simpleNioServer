package com.jun.threadedServer;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public class ThreadedServerTest {

    @Test(timeout = 5000)
    public void serverStartsAndStops() throws InterruptedException, IOException {
        // Bind to port 0 — let the OS pick a free port.
        ThreadedServer server = new ThreadedServer(0);
        try {
            server.start();
        } catch (RuntimeException e) {
            // Sandboxes / locked-down CI may forbid binds; skip in that case.
            org.junit.Assume.assumeNoException("environment forbids bind()", e);
        }

        int port = server.getBoundPort().orElseThrow(
                () -> new AssertionError("Server should be bound to an ephemeral port after start()."));
        assertTrue("Server should be bound to a positive port.", port > 0);
        assertFalse("Server should not be in 'stopped' state after start().", server.isStopped());
        assertNotNull("ServerSocket should be initialized after start().", server.serverSocket);
        assertFalse("ServerSocket should be open after start().", server.serverSocket.isClosed());

        try (Socket clientSocket = new Socket("localhost", port)) {
            assertTrue("Client should be able to connect to the started server.", clientSocket.isConnected());
        } catch (IOException e) {
            fail("Client should be able to connect to started server: " + e.getMessage());
        }

        server.stop();
        server.waitStop();

        assertTrue("Server should be in 'stopped' state after stop().", server.isStopped());
        assertTrue("ServerSocket should be closed after stop().", server.serverSocket.isClosed());

        server.threadPool.awaitTermination(1, TimeUnit.SECONDS);
        assertTrue("Thread pool should be shut down after server stops.", server.threadPool.isShutdown());
        assertTrue("Thread pool should be terminated after server stops and awaitTermination.", server.threadPool.isTerminated());
    }
}
