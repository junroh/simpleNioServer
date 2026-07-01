package com.jun.http;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * State-machine tests for {@link HttpRequestParser}. Same coverage that the old
 * NIO-bound HttpMessageReader had, but driven through the parser's NIO-agnostic
 * Listener API so it works for both server transports.
 */
public class HttpRequestParserTest {

    private List<HttpRequest> emitted;
    private List<String> errors;
    private HttpRequestParser parser;

    @Before
    public void setUp() {
        emitted = new ArrayList<>();
        errors = new ArrayList<>();
        parser = new HttpRequestParser(new HttpRequestParser.Listener() {
            @Override public void onRequest(HttpRequest req) { emitted.add(req); }
            @Override public void onError(String message) { errors.add(message); }
        });
    }

    private void feed(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        parser.feed(b, 0, b.length);
    }

    @Test
    public void parsesSimpleGet() {
        feed("GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n");

        assertEquals(1, emitted.size());
        HttpRequest r = emitted.get(0);
        assertEquals("GET", r.getMethod());
        assertEquals("/index.html", r.getRequestUri());
        assertEquals("HTTP/1.1", r.getHttpVersion());
        assertEquals("example.com", r.getHeaders().get("Host"));
        assertTrue(r.isKeepAlive());
        assertEquals(0, r.getBody().length);
    }

    @Test
    public void resumesWhenRequestLineSplitAcrossReads() {
        feed("GET /a");                           // partial request line
        assertTrue(emitted.isEmpty());
        feed(" HTTP/1.1\r\nHost: x\r\n\r\n");

        assertEquals(1, emitted.size());
        assertEquals("/a", emitted.get(0).getRequestUri());
    }

    @Test
    public void resumesWhenHeaderSplitMidLine() {
        feed("GET / HTTP/1.1\r\nHost: examp");   // exact bug the old parser had
        assertTrue(emitted.isEmpty());
        feed("le.com\r\nUser-Agent: t\r\n\r\n");

        assertEquals(1, emitted.size());
        assertEquals("example.com", emitted.get(0).getHeaders().get("Host"));
        assertEquals("t", emitted.get(0).getHeaders().get("User-Agent"));
    }

    @Test
    public void resumesWhenBodySplitAcrossReads() {
        feed("POST /x HTTP/1.1\r\nContent-Length: 11\r\n\r\nhello ");
        assertTrue(emitted.isEmpty());
        feed("world");

        assertEquals(1, emitted.size());
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), emitted.get(0).getBody());
    }

    @Test
    public void parsesChunkedRequest() {
        feed("POST /upload HTTP/1.1\r\n" +
             "Transfer-Encoding: chunked\r\n" +
             "\r\n" +
             "5\r\nhello\r\n" +
             "6\r\n world\r\n" +
             "0\r\n\r\n");

        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).getHeaders().chunkedTransferEncoding);
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), emitted.get(0).getBody());
    }

    @Test
    public void parsesChunkedSplitArbitrarily() {
        feed("POST /u HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nfo");
        assertTrue(emitted.isEmpty());
        feed("o\r\n0\r\n\r\n");

        assertEquals(1, emitted.size());
        assertArrayEquals("foo".getBytes(StandardCharsets.UTF_8), emitted.get(0).getBody());
    }

    @Test
    public void typedContentLengthSetRegardlessOfHeaderCase() {
        // Parser-specific: a differently-cased Content-Length still routes into the typed
        // field. (Case-insensitive raw lookup itself is covered by HttpHeadersTest.)
        feed("GET / HTTP/1.1\r\nCONTENT-LENGTH: 0\r\n\r\n");
        assertEquals(0, emitted.get(0).getHeaders().contentLength);
    }

    @Test
    public void http10DefaultsToConnectionClose() {
        feed("GET / HTTP/1.0\r\n\r\n");
        assertFalse(emitted.get(0).isKeepAlive());
    }

    @Test
    public void http11DefaultsToKeepAlive() {
        feed("GET / HTTP/1.1\r\n\r\n");
        assertTrue(emitted.get(0).isKeepAlive());
    }

    @Test
    public void connectionCloseHeaderOverridesDefault() {
        feed("GET / HTTP/1.1\r\nConnection: close\r\n\r\n");
        assertFalse(emitted.get(0).isKeepAlive());
    }

    @Test
    public void pipelinedRequestsBothEmittedFromOneFeed() {
        feed("GET /a HTTP/1.1\r\nHost: x\r\n\r\n" +
             "GET /b HTTP/1.1\r\nHost: y\r\n\r\n");

        assertEquals(2, emitted.size());
        assertEquals("/a", emitted.get(0).getRequestUri());
        assertEquals("/b", emitted.get(1).getRequestUri());
    }

    @Test
    public void malformedRequestLineCallsOnError() {
        feed("GARBAGE\r\n");
        assertFalse(errors.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void nonNumericContentLengthCallsOnError() {
        feed("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n");
        assertFalse("non-numeric Content-Length must error, not be ignored", errors.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void negativeContentLengthCallsOnError() {
        feed("POST /x HTTP/1.1\r\nContent-Length: -5\r\n\r\n");
        assertFalse(errors.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void oversizedContentLengthCallsOnError() {
        // Declared body far larger than MAX_REQUEST_SIZE (8 MiB) — reject up front, don't buffer.
        feed("POST /x HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n");
        assertFalse(errors.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void oversizedChunkedBodyCallsOnError() {
        // A single chunk whose data exceeds the 8 MiB cap must error mid-stream rather than
        // accumulate without bound.
        int big = 9 * 1024 * 1024;
        byte[] data = new byte[big];
        java.util.Arrays.fill(data, (byte) 'a');
        java.io.ByteArrayOutputStream req = new java.io.ByteArrayOutputStream();
        try {
            req.write(("POST /u HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + Integer.toHexString(big) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            req.write(data);
            req.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        byte[] bytes = req.toByteArray();
        parser.feed(bytes, 0, bytes.length);
        assertFalse("chunked body over the cap must error", errors.isEmpty());
        assertTrue(emitted.isEmpty());
    }

    @Test
    public void headerValueDecodedAsIso8859_1() {
        // 0xE9 is 'é' in ISO-8859-1; decoding the framing as the platform default (often UTF-8)
        // would mangle it. Build raw bytes since the feed() helper encodes as UTF-8.
        byte[] prefix = "GET / HTTP/1.1\r\nX-Tag: ".getBytes(StandardCharsets.US_ASCII);
        byte[] suffix = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[prefix.length + 1 + suffix.length];
        System.arraycopy(prefix, 0, raw, 0, prefix.length);
        raw[prefix.length] = (byte) 0xE9;
        System.arraycopy(suffix, 0, raw, prefix.length + 1, suffix.length);

        parser.feed(raw, 0, raw.length);

        assertEquals(1, emitted.size());
        assertEquals("é", emitted.get(0).getHeaders().get("X-Tag"));
    }
}
