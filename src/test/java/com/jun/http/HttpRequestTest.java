package com.jun.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HttpRequestTest {

    @Test
    public void exposesAllFieldsViaGetters() {
        HttpHeaders h = new HttpHeaders();
        h.put("Host", "example.com");
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        HttpRequest r = new HttpRequest("POST", "/api/echo", "HTTP/1.1", h, body);

        assertEquals("POST", r.getMethod());
        assertEquals("/api/echo", r.getRequestUri());
        assertEquals("HTTP/1.1", r.getHttpVersion());
        assertSame(h, r.getHeaders());
        assertSame(body, r.getBody());
    }

    @Test
    public void isKeepAliveReflectsHeaders() {
        HttpHeaders h1 = new HttpHeaders();   // default keepAlive=true
        HttpRequest r1 = new HttpRequest("GET", "/", "HTTP/1.1", h1, new byte[0]);
        assertTrue(r1.isKeepAlive());

        HttpHeaders h2 = new HttpHeaders();
        h2.keepAlive = false;
        HttpRequest r2 = new HttpRequest("GET", "/", "HTTP/1.0", h2, new byte[0]);
        assertFalse(r2.isKeepAlive());
    }

    @Test
    public void isHeadMatchesMethodCaseInsensitively() {
        HttpHeaders h = new HttpHeaders();
        assertTrue(new HttpRequest("HEAD", "/", "HTTP/1.1", h, new byte[0]).isHead());
        assertTrue(new HttpRequest("head", "/", "HTTP/1.1", h, new byte[0]).isHead());
        assertFalse(new HttpRequest("GET", "/", "HTTP/1.1", h, new byte[0]).isHead());
    }
}
