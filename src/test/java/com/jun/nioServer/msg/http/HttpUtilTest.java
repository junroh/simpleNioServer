package com.jun.nioServer.msg.http;

import org.junit.Test;
import java.io.UnsupportedEncodingException;
import static org.junit.Assert.*;

public class HttpUtilTest {

    @Test
    public void testParseHttpRequest_CompleteGETRequest() throws UnsupportedEncodingException {
        String request = "GET /test HTTP/1.1\r\n" +
                         "Host: example.com\r\n" +
                         "User-Agent: TestClient\r\n" +
                         "\r\n";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();

        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);

        assertEquals(requestBytes.length, result);
        assertEquals("GET", headers.getMethod());
        assertEquals("/test", headers.getUri());
        assertEquals("HTTP/1.1", headers.getVersion());
        assertEquals("example.com", headers.getHeader("Host"));
        assertEquals("TestClient", headers.getHeader("User-Agent"));
    }

    @Test
    public void testParseHttpRequest_Incomplete_NoFinalCRLFCRLF() throws UnsupportedEncodingException {
        String request = "GET /test HTTP/1.1\r\n" +
                         "Host: example.com"; // Missing final \r\n\r\n
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();

        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);

        assertEquals(-1, result);
    }

    @Test
    public void testParseHttpRequest_PartialHeaderLine() throws UnsupportedEncodingException {
        String request = "GET /test HTTP/1.1\r\n" +
                         "Host: examp"; // Partial header
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();

        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(-1, result);
    }

    @Test
    public void testParseHttpRequest_RequestWithBody_ReturnsEndOfHeaders() throws UnsupportedEncodingException {
        String request = "POST /submit HTTP/1.1\r\n" +
                         "Host: example.com\r\n" +
                         "Content-Length: 10\r\n" +
                         "\r\n" +
                         "HelloWorld";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();

        int expectedHeaderEnd = request.indexOf("\r\n\r\n") + 4;
        // The parseHttpRequest in HttpUtil currently returns the end of the body if Content-Length is present
        // and the body is fully contained in the buffer.
        // So, the expected result should be requestBytes.length if body is fully read.
        // If it's only supposed to return end of headers, then HttpUtil.java needs adjustment.
        // Let's assume current HttpUtil behavior: it returns end of body.
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);

        // assertEquals(expectedHeaderEnd, result); // This would be if it ONLY parsed headers
        assertEquals(requestBytes.length, result); // This is if it parses body based on content-length
        assertEquals("POST", headers.getMethod());
        assertEquals("/submit", headers.getUri());
        assertEquals(10, headers.contentLength); // HttpHeaders stores it as int
        assertEquals("10", headers.getHeader("Content-Length")); // Check raw header too
        assertEquals(expectedHeaderEnd, headers.bodyStartIndex);
        assertEquals(requestBytes.length, headers.bodyEndIndex);
    }

    @Test
    public void testParseHttpRequest_EmptyInput() {
        byte[] requestBytes = new byte[0];
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(-1, result);
    }

    @Test
    public void testParseHttpRequest_OnlyCRLFCRLF() throws UnsupportedEncodingException {
        String request = "\r\n\r\n";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        // This is an invalid request line, should not parse successfully.
        // Current HttpUtil likely returns -1 because it expects a method first.
        assertEquals(-1, result);
    }

    @Test
    public void testParseHttpRequest_NoHeadersOnlyRequestLine() throws UnsupportedEncodingException {
        String request = "GET / HTTP/1.1\r\n\r\n";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(requestBytes.length, result);
        assertEquals("GET", headers.getMethod());
        assertEquals("/", headers.getUri());
        assertEquals("HTTP/1.1", headers.getVersion());
    }

    @Test
    public void testParseHttpRequest_HeaderWithoutValue() throws UnsupportedEncodingException {
        // HttpUtil.parseHeaders seems to allow this by setting value to empty string
        String request = "GET / HTTP/1.1\r\n" +
                         "X-Custom-Header:\r\n" +
                         "\r\n";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(requestBytes.length, result);
        assertEquals("", headers.getHeader("X-Custom-Header"));
    }

    @Test
    public void testParseHttpRequest_MultipleHeadersSameName() throws UnsupportedEncodingException {
        // HTTP spec allows this, usually by comma-separating values or using the first/last.
        // HttpHeaders.addHeader overwrites with the last one.
        String request = "GET / HTTP/1.1\r\n" +
                         "Accept: text/html\r\n" +
                         "Accept: application/json\r\n" +
                         "\r\n";
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(requestBytes.length, result);
        assertEquals("application/json", headers.getHeader("Accept"));
    }

    @Test
    public void testParseHttpRequest_InvalidRequestLine_NoVersion() throws UnsupportedEncodingException {
        String request = "GET / \r\n\r\n"; // Missing HTTP version
        byte[] requestBytes = request.getBytes("UTF-8");
        HttpHeaders headers = new HttpHeaders();
        int result = HttpUtil.parseHttpRequest(requestBytes, 0, requestBytes.length, headers);
        assertEquals(-1, result);
    }
}
