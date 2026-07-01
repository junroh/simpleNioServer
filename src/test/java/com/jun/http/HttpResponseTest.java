package com.jun.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HttpResponseTest {

    @Test
    public void buildsMinimalResponse() {
        byte[] bytes = new HttpResponse().body("hi").toBytes();
        String s = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(s.contains("Content-Length: 2\r\n"));
        assertTrue(s.endsWith("\r\n\r\nhi"));
    }

    @Test
    public void includesAllHeadersInOrder() {
        byte[] bytes = new HttpResponse()
                .header("X-First", "1")
                .header("X-Second", "2")
                .body("ok")
                .toBytes();
        String s = new String(bytes, StandardCharsets.UTF_8);
        int firstIdx = s.indexOf("X-First: 1");
        int secondIdx = s.indexOf("X-Second: 2");
        assertTrue(firstIdx > 0);
        assertTrue(secondIdx > firstIdx);
    }

    @Test
    public void respectsExplicitContentLength() {
        byte[] bytes = new HttpResponse()
                .header("Content-Length", "999")
                .body("only-7")
                .toBytes();
        // Explicit Content-Length wins (we set it before body() so body() doesn't overwrite).
        String s = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(s.contains("Content-Length: 999\r\n"));
    }

    @Test
    public void customStatusAndVersion() {
        byte[] bytes = new HttpResponse()
                .httpVersion("HTTP/1.0")
                .status(404, "Not Found")
                .body("missing")
                .toBytes();
        String s = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.0 404 Not Found\r\n"));
    }

    @Test
    public void omitsBodyForHeadButKeepsContentLength() {
        byte[] bytes = new HttpResponse().body("hello").toBytes(true);
        String s = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue("HEAD response must still advertise the entity length",
                s.contains("Content-Length: 5\r\n"));
        assertTrue("HEAD response must end at the header terminator, no body",
                s.endsWith("\r\n\r\n"));
    }
}
