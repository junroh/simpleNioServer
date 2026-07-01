package com.jun.integration;

import com.jun.app.EchoHandler;
import com.jun.app.SimpleHelloHandler;
import com.jun.app.SlowHandler;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRouter;
import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.assertHelloOk;
import static com.jun.integration.support.TestClients.get;
import static com.jun.integration.support.TestClients.httpUrl;
import static org.junit.Assert.assertTrue;

/**
 * Server-side robustness — one bad client / handler must not bring down the server.
 * After each "bad" interaction we confirm the server still answers a normal request.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ResilienceIT {

    private static final String LOCALHOST  = "localhost";
    private static final String PATH_THROW = "/throw";
    private static final String PATH_SLEEP = "/sleep";

    // Stop-during-in-flight dials
    private static final int  SLEEP_HANDLER_MS         = 200;
    private static final long HANDLER_ENTRY_DELAY_MS   = 50;
    private static final long REQUESTER_JOIN_TIMEOUT_MS = 2_000;
    /** Stop ceiling — we expect the in-flight handler to finish (~SLEEP_HANDLER_MS) then
     *  the server to shut down. A generous bound that still catches "hang". */
    private static final long STOP_BUDGET_MS           = 3_000;

    private final TestServers servers = new TestServers();

    @After
    public void tearDown() {
        servers.stopAll();
    }

    @Test
    public void handlerThrows_serverKeepsServingOtherRequests() throws Exception {
        int port = startNioWithThrowingRoute();

        // First request hits the throwing handler — expect failure (connection close,
        // 5xx, or wrk-style read error). Whatever the exact wire, the test doesn't care.
        try {
            get(httpUrl(port, PATH_THROW));
        } catch (Exception ignored) {
            // Connection closed before response is also a valid failure mode here.
        }

        // Second request on the *same server* must still succeed.
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void clientDisconnectsMidRequest_serverKeepsServing() throws Exception {
        int port = startNioPlain();

        // Open a TCP socket, send a *partial* request line, then close. Server should
        // log the broken read and move on; no thread leak, no crash.
        try (Socket s = new Socket(LOCALHOST, port)) {
            s.getOutputStream().write("GET /hel".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            // Socket close via try-with-resources.
        }

        // Server must still answer normally on a fresh connection.
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void malformedRequest_serverKeepsServing() throws Exception {
        int port = startNioPlain();

        // Send garbage that is not a valid HTTP request line, then close.
        try (Socket s = new Socket(LOCALHOST, port)) {
            s.getOutputStream().write("NOT HTTP AT ALL\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            // Read briefly so server has a chance to react before we close.
            s.setSoTimeout(500);
            try { s.getInputStream().read(new byte[64]); } catch (Exception ignored) {}
        }

        // Server must still answer normally on a fresh connection.
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void stopDuringInFlightHandler_completesGracefully() throws Exception {
        // Server with a 200 ms sleeping handler on /sleep. Fire one request that will
        // sit inside the handler, then issue server.stop() while it's still sleeping.
        // The stop must return quickly — not hang waiting for the in-flight task.
        int port = startNioWithSleepRoute(SLEEP_HANDLER_MS);

        Thread requester = new Thread(() -> {
            try { get(httpUrl(port, PATH_SLEEP)); } catch (Exception ignored) {}
        }, "ResilienceIT-requester");
        requester.start();
        // Give the request time to reach the handler and enter its sleep.
        Thread.sleep(HANDLER_ENTRY_DELAY_MS);

        long t0 = System.nanoTime();
        servers.stopAll();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        requester.join(REQUESTER_JOIN_TIMEOUT_MS);

        assertTrue("server.stop() should complete quickly; took " + elapsedMs + " ms",
                elapsedMs < STOP_BUDGET_MS);
    }

    private int startNioPlain() {
        return servers.startNio(buildHelloRouter(), false);
    }

    private int startNioWithThrowingRoute() {
        HttpRequestHandler router = new HttpRouter()
                .get(PATH_HELLO, new SimpleHelloHandler(TestServers.HELLO_BODY_FORMAT, 0))
                .get(PATH_THROW, req -> { throw new RuntimeException("simulated handler failure"); });
        return servers.startNio(router, false);
    }

    private int startNioWithSleepRoute(int sleepMs) {
        HttpRequestHandler router = new HttpRouter()
                .get(PATH_HELLO, new SimpleHelloHandler(TestServers.HELLO_BODY_FORMAT, 0))
                .get(PATH_SLEEP, new SlowHandler(sleepMs));
        return servers.startNio(router, false);
    }

    private static HttpRequestHandler buildHelloRouter() {
        return new HttpRouter()
                .get(PATH_HELLO, new SimpleHelloHandler(TestServers.HELLO_BODY_FORMAT, 0))
                .get("/echo", new EchoHandler())
                .post("/echo", new EchoHandler());
    }
}
