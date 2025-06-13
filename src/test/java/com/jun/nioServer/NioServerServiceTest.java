package com.jun.nioServer;

import com.jun.config.ServerConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

public class NioServerServiceTest {

    private int originalPort;
    private boolean originalSslEnabled;
    private String originalAcceptorAddress;
    private int testPort;

    @Before
    public void setUp() {
        // Store original ServerConfig values that might be changed for the test
        originalPort = ServerConfig.NIO_SERVER_PORT;
        originalSslEnabled = ServerConfig.NIO_SERVER_SSL_ENABLED;
        originalAcceptorAddress = ServerConfig.NIO_ACCEPTOR_ADDRESS;

        // Configure for testing: use a different port, disable SSL for simplicity
        testPort = originalPort + 300; // Choose a port likely not in use
        if (testPort > 65535) testPort = 18888; // Fallback test port

        ServerConfig.NIO_SERVER_PORT = testPort;
        ServerConfig.NIO_SERVER_SSL_ENABLED = false;
        ServerConfig.NIO_ACCEPTOR_ADDRESS = "localhost"; // Ensure it binds to localhost for test
    }

    @After
    public void tearDown() {
        // Restore original ServerConfig values
        ServerConfig.NIO_SERVER_PORT = originalPort;
        ServerConfig.NIO_SERVER_SSL_ENABLED = originalSslEnabled;
        ServerConfig.NIO_ACCEPTOR_ADDRESS = originalAcceptorAddress;
    }

    @Test(timeout = 20000) // Generous timeout for service start/stop
    public void testServiceStartsRunsAndStopsCleanly() throws Exception {
        // Check if port is available before starting the test
        try (ServerSocket checkSocket = new ServerSocket()) {
            checkSocket.bind(new InetSocketAddress(ServerConfig.NIO_ACCEPTOR_ADDRESS, ServerConfig.NIO_SERVER_PORT));
            // If bind succeeds, port is available. Close it immediately.
        } catch (IOException e) {
            System.err.println("NioServerServiceTest: Port " + ServerConfig.NIO_SERVER_PORT +
                               " is already in use. Skipping testServiceStartsRunsAndStopsCleanly.");
            // Using org.junit.Assume.assumeNoException(e) would be better if allowed by environment
            return; // Skip test if port is not available
        }

        // NioServerService constructor now takes ServerConfig.
        // Pass a new instance; NioServerService will read static fields from ServerConfig class as implemented.
        NioServerService service = new NioServerService(new ServerConfig());

        Thread serviceThread = new Thread(() -> {
            try {
                service.start();    // This will internally do all setup
                service.waitStop(); // This blocks until stop() is effectively called and processed
            } catch (Exception e) {
                e.printStackTrace();
                // Use a static boolean flag or a more sophisticated mechanism if you need to assert this in the main thread.
                // For now, printing stack trace might be the best we can do for background thread exceptions.
                // Or, rethrow as a RuntimeException to make the serviceThread fail loudly if possible,
                // though run() signatures don't allow checked exceptions.
                // Consider a shared AtomicReference<Throwable> to capture exceptions.
                NioServerServiceTest.logThreadException(e);
            }
        });

        serviceThread.setName("Test-NioServerService-Thread");
        serviceThread.start();

        // Give the server some time to start up fully
        Thread.sleep(1000); // Adjust if needed, can be flaky
        assertNull("Service thread should not have thrown an exception during startup", threadException.get());


        // Try to connect a client
        boolean connected = false;
        try (Socket clientSocket = new Socket()) {
            clientSocket.connect(new InetSocketAddress(ServerConfig.NIO_ACCEPTOR_ADDRESS, ServerConfig.NIO_SERVER_PORT), 1000); // 1s timeout
            connected = clientSocket.isConnected();
        } catch (IOException e) {
            // This might happen if server is slow to start, or if there's an issue.
            System.err.println("Client connection attempt failed during test: " + e.getMessage());
        }
        assertTrue("Client should be able to connect to the started NioServerService", connected);
        assertNull("Service thread should not have thrown an exception during client connection phase", threadException.get());

        // Stop the service
        service.stop();

        // Wait for the service thread to die
        serviceThread.join(5000); // Wait up to 5 seconds for thread to die

        assertFalse("Service thread should have stopped after service.stop() and join.", serviceThread.isAlive());
        assertNull("Service thread should not have thrown an exception during stop/shutdown", threadException.get());


        // Verify port is released
        try (ServerSocket checkSocket = new ServerSocket()) {
            checkSocket.bind(new InetSocketAddress(ServerConfig.NIO_ACCEPTOR_ADDRESS, ServerConfig.NIO_SERVER_PORT));
            // If bind succeeds, port is now available again.
        } catch (IOException e) {
            fail("Port " + ServerConfig.NIO_SERVER_PORT + " should be released after server stops, but was still in use: " + e.getMessage());
        }
        System.out.println("NioServerServiceTest: testServiceStartsRunsAndStopsCleanly completed successfully.");
    }

    // Helper to capture exceptions from the service thread
    private static java.util.concurrent.atomic.AtomicReference<Throwable> threadException = new java.util.concurrent.atomic.AtomicReference<>(null);
    private static void logThreadException(Throwable e) {
        threadException.set(e);
    }
    // Reset before each test if needed, though with one test, it's okay.
    // If adding more tests to this class, ensure threadException is reset in @Before.
    // @Before public void resetException() { threadException.set(null); }


    // Note: This test covers the basic lifecycle. More specific tests for different configurations
    // (e.g., SSL enabled, specific error conditions during startup) would require more focused setups.
    // Testing the internal logic of resource creation (IOReactors, Acceptor) is challenging without
    // making them more visible or using more advanced mocking/DI for those internal parts.
}
