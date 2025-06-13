package com.jun.threadedServer;

import com.jun.config.ServerConfig;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

// Testing multithreaded server lifecycle can be complex.
// These tests will be basic and might need adjustments for reliability.

public class ThreadedServerTest {

    @Test(timeout = 5000) // Timeout to prevent test hanging
    public void testServerStartsAndStops() throws InterruptedException, IOException {
        // Use a different port for testing to avoid conflicts
        int testPort = ServerConfig.THREADED_SERVER_PORT + 100;
        if (testPort > 65535) testPort = 18080; // fallback test port

        ThreadedServer server = null;
        ServerSocket checkSocket = null;
        boolean portInUse = true;
        try {
            // Check if port is already in use
            checkSocket = new ServerSocket(testPort);
            portInUse = false; // Port is available
        } catch (IOException e) {
            System.err.println("ThreadedServerTest: Port " + testPort + " is already in use. Skipping testServerStartsAndStops.");
            // This test cannot run if port is in use.
            return;
        } finally {
            if (checkSocket != null && !portInUse) {
                checkSocket.close();
            }
        }

        server = new ThreadedServer(testPort);
        server.start(); // Start the server thread

        // Give server time to start
        Thread.sleep(500);

        // assertTrue("Server thread should be alive after start().", server.server.isAlive()); // Check via behavior (connect) and isStopped()
        assertFalse("Server should not be in 'stopped' state after start().", server.isStopped());
        assertNotNull("ServerSocket should be initialized after start().", server.serverSocket);
        assertFalse("ServerSocket should be open after start().", server.serverSocket.isClosed());

        // Try to connect to the server briefly
        try (Socket clientSocket = new Socket("localhost", testPort)) {
            assertTrue("Client should be able to connect to the started server.", clientSocket.isConnected());
        } catch (IOException e) {
            fail("Client should be able to connect to started server: " + e.getMessage());
        }

        server.stop(); // Request server to stop
        // server.waitStop(); // waitStop is called by ThreadedServer.stop() internally via server.interrupt() and then join logic is in test.
                           // Actually, ThreadedServer.stop() does server.interrupt() and then serverSocket.close().
                           // The waitStop() method in ThreadedServer calls server.join().
                           // For this test, let's call waitStop() to ensure thread completion.
        server.waitStop();


        // assertFalse("Server thread should be stopped after stop() and waitStop().", server.server.isAlive()); // waitStop() ensures thread termination.
        assertTrue("Server should be in 'stopped' state after stop().", server.isStopped());
        assertTrue("ServerSocket should be closed after stop().", server.serverSocket.isClosed());

        // Check thread pool shutdown state
        // ThreadedServer.run() calls this.threadPool.shutdown() when isStopped becomes true and loop exits.
        // ThreadedServer.stop() sets isStopped=true and interrupts, so pool should shutdown.
        server.threadPool.awaitTermination(1, TimeUnit.SECONDS); // Give some time for pool to terminate
        assertTrue("Thread pool should be shut down after server stops.", server.threadPool.isShutdown());
        assertTrue("Thread pool should be terminated after server stops and awaitTermination.", server.threadPool.isTerminated());
    }
}
