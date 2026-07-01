package com.jun.app;

import com.jun.http.HttpHeaders;
import com.jun.http.HttpRequest;
import com.jun.http.HttpResponse;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class SimpleHelloHandlerTest {

    private static HttpRequest req() {
        return new HttpRequest("GET", "/", "HTTP/1.1", new HttpHeaders(), new byte[0]);
    }

    @Test
    public void returns200WithHtmlBody() {
        SimpleHelloHandler h = new SimpleHelloHandler("body-%d-%d", 7);
        HttpResponse r = h.handle(req());
        String wire = new String(r.toBytes(), StandardCharsets.UTF_8);

        assertTrue(wire.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(wire.contains("Content-Type: text/html\r\n"));
    }

    @Test
    public void bodyContainsTransportAndRequestId() {
        SimpleHelloHandler h = new SimpleHelloHandler("hello %d-%d", 42);
        HttpResponse r = h.handle(req());
        String wire = new String(r.toBytes(), StandardCharsets.UTF_8);
        // First request → counter starts at 1.
        assertTrue("expected 'hello 42-1' in body, got: " + wire, wire.contains("hello 42-1"));
    }

    @Test
    public void counterIncrementsAcrossRequests() {
        SimpleHelloHandler h = new SimpleHelloHandler("id=%d-%d", 0);
        String wire1 = new String(h.handle(req()).toBytes(), StandardCharsets.UTF_8);
        String wire2 = new String(h.handle(req()).toBytes(), StandardCharsets.UTF_8);
        String wire3 = new String(h.handle(req()).toBytes(), StandardCharsets.UTF_8);

        assertTrue(wire1.contains("id=0-1"));
        assertTrue(wire2.contains("id=0-2"));
        assertTrue(wire3.contains("id=0-3"));
    }
}
