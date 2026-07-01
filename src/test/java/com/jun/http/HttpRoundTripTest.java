package com.jun.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration test: wire {@link HttpRequestParser} → {@link HttpRequestHandler} →
 * {@link HttpResponse#toBytes()} into a single pipeline. Then re-parse the response
 * bytes manually to verify the wire format is consumable by an HTTP client.
 *
 * <p>The point of this test is to prove the {@code com.jun.http} library composes
 * end-to-end on its own — no NIO, no threaded server, no sockets.
 */
public class HttpRoundTripTest {

    @Test
    public void parsedRequestRoundTripsThroughHandlerToWireBytes() {
        // 1) Parse the request from raw bytes.
        List<HttpRequest> parsed = new ArrayList<>();
        HttpRequestParser parser = new HttpRequestParser(new HttpRequestParser.Listener() {
            @Override public void onRequest(HttpRequest req) { parsed.add(req); }
            @Override public void onError(String msg) { fail("parse error: " + msg); }
        });
        byte[] reqBytes = (
                "POST /echo HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "hello world").getBytes(StandardCharsets.UTF_8);
        parser.feed(reqBytes, 0, reqBytes.length);
        assertEquals(1, parsed.size());

        // 2) Run the application handler — an echo that ships the body back.
        HttpRequestHandler echo = req -> new HttpResponse()
                .status(200, "OK")
                .header("Content-Type", "text/plain")
                .header("X-Method", req.getMethod())
                .header("X-Uri", req.getRequestUri())
                .body(req.getBody());
        HttpResponse resp = echo.handle(parsed.get(0));

        // 3) Serialize to wire bytes.
        byte[] wire = resp.toBytes();
        String s = new String(wire, StandardCharsets.UTF_8);

        // 4) Verify the wire format is what an HTTP client would see.
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Content-Type: text/plain\r\n"));
        assertTrue(s.contains("X-Method: POST\r\n"));
        assertTrue(s.contains("X-Uri: /echo\r\n"));
        assertTrue(s.contains("Content-Length: 11\r\n"));

        int blank = s.indexOf("\r\n\r\n");
        String body = s.substring(blank + 4);
        assertEquals("hello world", body);
    }
}
