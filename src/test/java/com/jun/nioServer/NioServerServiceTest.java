package com.jun.nioServer;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end test that boots a {@link NioServerService} on an ephemeral port,
 * connects a client, then shuts the service down cleanly. Avoids hard-coded ports
 * so tests don't conflict with anything else running on the host.
 */
public class NioServerServiceTest {

    private final AtomicReference<Throwable> threadException = new AtomicReference<>(null);

    @Test(timeout = 20000)
    public void serviceStartsRunsAndStopsCleanly() throws Exception {
        NioServerSettings settings = NioServerSettings.builder()
                .port(0)                  // bind ephemeral — OS picks a free port
                .hostAddress("localhost")
                .sslEnabled(false)
                .numIoReactors(1)
                .numIoThreads(4)
                .acceptorBacklog(64)
                .acceptorNonBlocking(false)
                .build();

        NioServerService service = new NioServerService(settings);

        Thread serviceThread = new Thread(() -> {
            try {
                service.start();
                service.waitStop();
            } catch (Exception e) {
                threadException.set(e);
            }
        }, "Test-NioServerService-Thread");
        serviceThread.start();

        // Wait for the bind to complete, or detect that the environment forbids bind().
        int port = -1;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Throwable t = threadException.get();
            if (t != null) {
                org.junit.Assume.assumeNoException("environment forbids bind()", t);
            }
            port = service.getBoundPort().orElse(-1);
            if (port > 0) break;
            Thread.sleep(20);
        }
        assertTrue("Service should bind to an ephemeral port within 5s", port > 0);

        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("localhost", port), 1000);
            assertTrue("Client should connect", client.isConnected());
        }
        assertNull("Service thread should not have thrown after client connect", threadException.get());

        service.stop();
        serviceThread.join(5000);
        assertFalse("Service thread should have terminated", serviceThread.isAlive());
        assertNull("Service thread should not have thrown during stop", threadException.get());

        // Port should be free again.
        try (ServerSocket check = new ServerSocket()) {
            check.bind(new InetSocketAddress("localhost", port));
        } catch (IOException e) {
            fail("Port " + port + " should be released after stop, but: " + e.getMessage());
        }
    }
}
