package com.jun.integration;

import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

import static org.junit.Assert.fail;

/**
 * Stress/regression test for intermittent NIO+SSL server hangs under rapid HTTPS connection
 * churn. The bug it guards: the blocking SSL handshake in {@code SSLEngineBuffer} read from
 * the socket on every {@code NEED_UNWRAP} step even when the peer's flight had already arrived
 * coalesced and sat unprocessed in the net-in buffer — so the handshake parked in a blocking
 * read waiting for bytes it already had, until the client gave up (~2s) and closed. The server
 * then "stopped responding" for that connection.
 *
 * <p>Drives many short-lived HTTPS request/close cycles (raw {@link SSLSocket}, request written
 * immediately after the handshake, {@code Connection: close} so the server tears the connection
 * down right after responding). Intermittent before the fix (~1 run in 10); must be hang-free now.
 */
public class HttpsChurnStressIT {

    private static final int ATTEMPTS = 50;
    private static final int READ_TIMEOUT_MS = 2_000;
    private static final String REQUEST =
            "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";

    private final TestServers servers = new TestServers();

    @After
    public void tearDown() { servers.stopAll(); }

    @Test
    public void rapidRequestThenClose_neverHangs() throws Exception {
        int port = servers.startNio(true, false);   // SSL on, async off
        SSLSocketFactory factory = trustAllFactory();

        for (int i = 1; i <= ATTEMPTS; i++) {
            try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
                socket.connect(new InetSocketAddress("localhost", port), READ_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                socket.startHandshake();

                OutputStream out = socket.getOutputStream();
                out.write(REQUEST.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                String response = readToEnd(socket.getInputStream());
                if (!response.contains("HTTP/1.1 200")) {
                    fail("attempt " + i + ": expected 200, got:\n" + response);
                }
            } catch (SocketTimeoutException e) {
                fail("attempt " + i + ": server stopped responding — " + e);
            }
        }
    }

    /** Read until the server closes the connection (Connection: close) or the socket times out. */
    private static String readToEnd(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    private static SSLSocketFactory trustAllFactory() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) { }

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) { }
        }}, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }
}
