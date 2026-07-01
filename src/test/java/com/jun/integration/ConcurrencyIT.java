package com.jun.integration;

import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jun.integration.support.TestClients.HELLO_BODY_MARKER;
import static com.jun.integration.support.TestClients.HTTP_OK;
import static com.jun.integration.support.TestClients.PATH_ECHO;
import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.get;
import static com.jun.integration.support.TestClients.httpUrl;
import static com.jun.integration.support.TestClients.post;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Stress the NIO transport's concurrent and multi-request paths. Verifies that:
 * <ul>
 *   <li>10 parallel GETs all succeed on one server.</li>
 *   <li>Mixed POST + GET in parallel exercise both reader and writer pools.</li>
 *   <li>A 10 KB body round-trips intact through the partial-read / partial-write paths.</li>
 *   <li>Three pipelined requests on one TCP connection produce three responses
 *       (verifies the parser's keep-alive multi-request state machine).</li>
 * </ul>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ConcurrencyIT {

    private static final int CONCURRENT_GETS_ONLY    = 10;
    private static final int MIXED_POSTERS           = 10;
    private static final int MIXED_GETTERS          = 10;
    private static final int LARGE_BODY_BYTES        = 10_000;
    private static final int PIPELINED_REQUEST_COUNT = 3;
    private static final int READ_BUFFER_SIZE        = 4096;

    private static final long CONCURRENT_DEADLINE_SECONDS = 10;
    private static final long MIXED_DEADLINE_SECONDS      = 15;
    private static final int  RAW_SOCKET_SO_TIMEOUT_MS    = 5_000;

    private static final String LOCALHOST            = "localhost";
    private static final String CRLF                  = "\r\n";
    private static final String REQ_HEADER_KEEP_ALIVE =
            "GET " + PATH_HELLO + " HTTP/1.1" + CRLF + "Host: " + LOCALHOST + CRLF + CRLF;
    private static final String REQ_HEADER_CLOSE      =
            "GET " + PATH_HELLO + " HTTP/1.1" + CRLF + "Host: " + LOCALHOST + CRLF
            + "Connection: close" + CRLF + CRLF;
    private static final String RESPONSE_STATUS_LINE  = "HTTP/1.1 200";

    private final TestServers servers = new TestServers();

    @After public void tearDown() { servers.stopAll(); }

    @Test
    public void concurrent10Gets_allSucceed() throws Exception {
        int port = servers.startNio(false, false);
        ExecutorService exec = Executors.newFixedThreadPool(CONCURRENT_GETS_ONLY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENT_GETS_ONLY);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int i = 0; i < CONCURRENT_GETS_ONLY; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        HttpResponse<String> r = get(httpUrl(port, PATH_HELLO));
                        if (r.statusCode() == HTTP_OK && r.body().contains(HELLO_BODY_MARKER)) {
                            ok.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("requests timed out", done.await(CONCURRENT_DEADLINE_SECONDS, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
        assertEquals("expected all " + CONCURRENT_GETS_ONLY + " requests to succeed",
                CONCURRENT_GETS_ONLY, ok.get());
    }

    @Test
    public void concurrentMixedPostAndGet_allSucceed() throws Exception {
        int port = servers.startNio(false, false);
        int total = MIXED_POSTERS + MIXED_GETTERS;
        ExecutorService exec = Executors.newFixedThreadPool(total);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger postOk = new AtomicInteger();
        AtomicInteger getOk = new AtomicInteger();
        try {
            for (int i = 0; i < MIXED_POSTERS; i++) {
                final String payload = "post-" + i;
                exec.submit(() -> {
                    try {
                        start.await();
                        HttpResponse<String> r = post(httpUrl(port, PATH_ECHO), payload);
                        if (r.statusCode() == HTTP_OK && r.body().contains(payload)) {
                            postOk.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            for (int i = 0; i < MIXED_GETTERS; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        HttpResponse<String> r = get(httpUrl(port, PATH_HELLO));
                        if (r.statusCode() == HTTP_OK && r.body().contains(HELLO_BODY_MARKER)) {
                            getOk.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue("mixed requests timed out",
                    done.await(MIXED_DEADLINE_SECONDS, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
        assertEquals("expected all " + MIXED_POSTERS + " POSTs to succeed",
                MIXED_POSTERS, postOk.get());
        assertEquals("expected all " + MIXED_GETTERS + " GETs to succeed",
                MIXED_GETTERS, getOk.get());
    }

    @Test
    public void largeBody10KB_postEcho_roundTrips() throws Exception {
        int port = servers.startNio(false, false);
        String payload = repeatingAlphabet(LARGE_BODY_BYTES);
        HttpResponse<String> resp = post(httpUrl(port, PATH_ECHO), payload);
        assertEquals(HTTP_OK, resp.statusCode());
        assertEquals("body length should match", payload.length(), resp.body().length());
        assertEquals("body content should match", payload, resp.body());
    }

    @Test
    public void pipelined3Requests_oneConnection_threeResponses() throws Exception {
        int port = servers.startNio(false, false);
        StringBuilder requests = new StringBuilder();
        for (int i = 0; i < PIPELINED_REQUEST_COUNT - 1; i++) {
            requests.append(REQ_HEADER_KEEP_ALIVE);
        }
        requests.append(REQ_HEADER_CLOSE);

        try (Socket socket = new Socket(LOCALHOST, port)) {
            socket.setSoTimeout(RAW_SOCKET_SO_TIMEOUT_MS);
            socket.getOutputStream().write(requests.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buf = new byte[READ_BUFFER_SIZE];
            int n;
            while ((n = socket.getInputStream().read(buf)) > 0) {
                sink.write(buf, 0, n);
            }
            String all = sink.toString(StandardCharsets.US_ASCII);

            assertEquals("expected " + PIPELINED_REQUEST_COUNT + " status lines, raw=\n" + all,
                    PIPELINED_REQUEST_COUNT, countOccurrences(all, RESPONSE_STATUS_LINE));
            assertEquals("expected " + PIPELINED_REQUEST_COUNT + " body matches, raw=\n" + all,
                    PIPELINED_REQUEST_COUNT, countOccurrences(all, HELLO_BODY_MARKER));
        }
    }

    private static String repeatingAlphabet(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        return sb.toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
