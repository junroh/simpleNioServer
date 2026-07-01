package com.jun.threadedServer;

import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;
import com.jun.app.SimpleHelloHandler;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * End-to-end tests for the threaded HTTP loop. {@link HttpWorker#processStream} reads
 * HTTP requests from an {@link InputStream}, dispatches each to the
 * {@link HttpRequestHandler}, and writes responses to the {@link OutputStream}.
 *
 * <p>By driving it with {@link ByteArrayInputStream} / {@link ByteArrayOutputStream},
 * we can exercise the full parser→handler→response pipeline without binding a socket.
 */
public class HttpWorkerTest {

    private static byte[] runOnce(String requestWire, HttpRequestHandler handler) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(requestWire.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpWorker.processStream(in, out, handler);
        return out.toByteArray();
    }

    @Test
    public void respondsToSimpleGet() throws IOException {
        SimpleHelloHandler app = new SimpleHelloHandler("hello-%d-%d", 0);

        // No keep-alive → close after one request, EOF on next read.
        byte[] response = runOnce(
                "GET /index HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n",
                app);
        String wire = new String(response, StandardCharsets.UTF_8);

        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(wire.contains("hello-0-1"));
    }

    @Test
    public void honorsKeepAliveAcrossPipelinedRequests() throws IOException {
        // Three requests on one connection. Default for HTTP/1.1 is keep-alive.
        // Use a handler that records every request it sees.
        List<String> seenUris = new ArrayList<>();
        HttpRequestHandler app = new HttpRequestHandler() {
            @Override public HttpResponse handle(HttpRequest request) {
                seenUris.add(request.getRequestUri());
                return new HttpResponse().status(200, "OK").body("ok");
            }
        };

        byte[] response = runOnce(
                "GET /a HTTP/1.1\r\nHost: x\r\n\r\n" +
                "GET /b HTTP/1.1\r\nHost: x\r\n\r\n" +
                "GET /c HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n",
                app);

        assertEquals(3, seenUris.size());
        assertEquals("/a", seenUris.get(0));
        assertEquals("/b", seenUris.get(1));
        assertEquals("/c", seenUris.get(2));

        // Output must contain three response status lines.
        String wire = new String(response, StandardCharsets.UTF_8);
        int lineCount = wire.split("HTTP/1\\.1 200 OK\r\n", -1).length - 1;
        assertEquals(3, lineCount);
    }

    @Test
    public void closesAfterFirstRequestWithConnectionClose() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpRequestHandler app = req -> {
            calls.incrementAndGet();
            return new HttpResponse().status(200, "OK").body("ok");
        };

        // Two requests in input — but the first asks for close, so the second is never handled.
        runOnce(
                "GET /first HTTP/1.1\r\nConnection: close\r\n\r\n" +
                "GET /second HTTP/1.1\r\n\r\n",
                app);

        assertEquals(1, calls.get());
    }

    @Test
    public void parsesPostBodyAndEchoesIt() throws IOException {
        // Echo handler returns the body bytes back.
        HttpRequestHandler echo = req -> new HttpResponse()
                .status(200, "OK")
                .body(req.getBody());

        byte[] response = runOnce(
                "POST /echo HTTP/1.1\r\nContent-Length: 11\r\nConnection: close\r\n\r\nhello world",
                echo);
        String wire = new String(response, StandardCharsets.UTF_8);
        // Body section is everything after the blank line in the response.
        int blank = wire.indexOf("\r\n\r\n");
        String body = wire.substring(blank + 4);
        assertEquals("hello world", body);
    }

    @Test
    public void headRequestReturnsHeadersWithoutBody() throws IOException {
        HttpRequestHandler app = req -> new HttpResponse().status(200, "OK").body("hello");
        byte[] response = runOnce(
                "HEAD /index HTTP/1.1\r\nConnection: close\r\n\r\n",
                app);
        String wire = new String(response, StandardCharsets.UTF_8);

        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue("HEAD must advertise the entity length", wire.contains("Content-Length: 5\r\n"));
        assertTrue("HEAD must send no body", wire.endsWith("\r\n\r\n"));
    }

    @Test
    public void terminatesOnEofWithNoRequest() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpRequestHandler app = req -> {
            calls.incrementAndGet();
            return new HttpResponse().body("ok");
        };
        // Empty input → loop exits immediately on EOF.
        byte[] response = runOnce("", app);
        assertEquals(0, calls.get());
        assertEquals(0, response.length);
    }

    @Test
    public void parserErrorTerminatesLoop() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpRequestHandler app = req -> {
            calls.incrementAndGet();
            return new HttpResponse().body("ok");
        };
        runOnce("GARBAGE\r\n\r\n", app);
        assertEquals(0, calls.get());
    }
}
