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

import static com.jun.integration.support.TestClients.ECHO_QUERY_MARKER;
import static com.jun.integration.support.TestClients.HELLO_BODY_MARKER;
import static com.jun.integration.support.TestClients.HTTP_NOT_FOUND;
import static com.jun.integration.support.TestClients.HTTP_OK;
import static com.jun.integration.support.TestClients.PATH_ECHO;
import static com.jun.integration.support.TestClients.PATH_ECHO_QUERY;
import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.PATH_MISSING;
import static com.jun.integration.support.TestClients.get;
import static com.jun.integration.support.TestClients.httpUrl;
import static com.jun.integration.support.TestClients.post;
import static com.jun.integration.support.TestClients.put;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTTP method + routing + wire-level semantics on the NIO transport. NIO is enough —
 * routing is transport-independent, and the parser + dispatcher path is the same for
 * all transports under the hood.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HttpSemanticsIT {

    private static final String LOCALHOST = "localhost";
    private static final String CRLF      = "\r\n";

    private final TestServers servers = new TestServers();

    @After public void tearDown() { servers.stopAll(); }

    @Test
    public void getEcho_returnsRequestUriWithQuery() throws Exception {
        int port = servers.startNio(false, false);
        HttpResponse<String> resp = get(httpUrl(port, PATH_ECHO_QUERY));
        assertEquals(HTTP_OK, resp.statusCode());
        // EchoHandler: empty body → URI is echoed. Query string is preserved.
        assertTrue("body should echo the URI (with query), got: " + resp.body(),
                resp.body().contains(PATH_ECHO) && resp.body().contains(ECHO_QUERY_MARKER));
    }

    @Test
    public void postEcho_returnsRequestBody() throws Exception {
        int port = servers.startNio(false, false);
        String payload = "payload-X";
        HttpResponse<String> resp = post(httpUrl(port, PATH_ECHO), payload);
        assertEquals(HTTP_OK, resp.statusCode());
        assertTrue("body should echo payload, got: " + resp.body(), resp.body().contains(payload));
    }

    @Test
    public void putEcho_returnsRequestBody() throws Exception {
        int port = servers.startNio(false, false);
        String payload = "put-payload";
        HttpResponse<String> resp = put(httpUrl(port, PATH_ECHO), payload);
        assertEquals(HTTP_OK, resp.statusCode());
        assertTrue("body should echo payload, got: " + resp.body(), resp.body().contains(payload));
    }

    @Test
    public void unknownPath_returns404() throws Exception {
        int port = servers.startNio(false, false);
        assertEquals(HTTP_NOT_FOUND, get(httpUrl(port, PATH_MISSING)).statusCode());
    }

    @Test
    public void chunkedPost_isReassembled() throws Exception {
        // Raw wire so we can force Transfer-Encoding: chunked (the JDK HttpClient does
        // not chunk small bodies). Verifies the parser's chunked-body branch.
        int port = servers.startNio(false, false);
        String wire =
                "POST " + PATH_ECHO + " HTTP/1.1" + CRLF
              + "Host: " + LOCALHOST + CRLF
              + "Transfer-Encoding: chunked" + CRLF
              + "Connection: close" + CRLF + CRLF
              + "5" + CRLF + "hello" + CRLF
              + "6" + CRLF + " world" + CRLF
              + "0" + CRLF + CRLF;
        String response = exchange(port, wire);
        assertTrue("expected 200 OK, raw=\n" + response, response.contains("HTTP/1.1 200"));
        assertTrue("expected reassembled body 'hello world', raw=\n" + response,
                response.contains("hello world"));
    }

    @Test
    public void http10Request_serverClosesAfterResponse() throws Exception {
        // HTTP/1.0 default is connection-close. Server must send the response and then
        // close the socket — read() returns -1 quickly, not after a timeout.
        int port = servers.startNio(false, false);
        String wire = "GET " + PATH_HELLO + " HTTP/1.0" + CRLF
              + "Host: " + LOCALHOST + CRLF + CRLF;
        String response = exchange(port, wire);
        assertTrue("expected 200 OK, raw=\n" + response, response.contains("HTTP/1.1 200"));
        assertTrue("expected hello body, raw=\n" + response,
                response.contains(HELLO_BODY_MARKER));
    }

    /** Send {@code wire} on a raw socket and read until the server closes. Returns the
     *  full response bytes as a US-ASCII string. */
    private static String exchange(int port, String wire) throws Exception {
        try (Socket socket = new Socket(LOCALHOST, port)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) > 0) {
                sink.write(buf, 0, n);
            }
            return sink.toString(StandardCharsets.US_ASCII);
        }
    }
}
