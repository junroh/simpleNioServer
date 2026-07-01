package com.jun.nioServer.ssl;

import com.jun.nioServer.Connection;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link SSLEngineBuffer}'s steady-state unwrap/wrap. Drives a peer-to-peer
 * handshake in-memory using JDK SSLEngines, then exercises the buffer:
 *
 * <ul>
 *   <li>Round-trips one record cleanly.</li>
 *   <li>Splits an encrypted record across two unwrap() calls — the partial bytes from
 *       the first call must be retained so the second call can complete the record.</li>
 *   <li>Multiple records arriving in one socket read are all unwrapped.</li>
 * </ul>
 */
public class SSLEngineBufferTest {

    private SSLContext sslContext;
    private Connection mockConnection;

    @Before
    public void setUp() throws Exception {
        sslContext = loadServerContext();
        mockConnection = mock(Connection.class);
    }

    @Test
    public void unwrap_roundTripsOneRecord() throws Exception {
        EnginePair pair = handshake();
        SSLEngineBuffer serverBuffer = new SSLEngineBuffer(pair.server, mockConnection);

        byte[] payload = "hello tls".getBytes(StandardCharsets.UTF_8);
        ByteBuffer encrypted = clientEncrypt(pair.client, payload);

        List<ByteBuffer> decrypted = serverBuffer.unwrap(encrypted);

        assertEquals(1, decrypted.size());
        assertArrayEquals(payload, toByteArray(decrypted.get(0)));
    }

    @Test
    public void unwrap_handlesSplitRecord() throws Exception {
        EnginePair pair = handshake();
        SSLEngineBuffer serverBuffer = new SSLEngineBuffer(pair.server, mockConnection);

        byte[] payload = "hello split record across two reads".getBytes(StandardCharsets.UTF_8);
        ByteBuffer fullEncrypted = clientEncrypt(pair.client, payload);

        // Split the encrypted stream into two halves. The first half is a partial record;
        // the buffer must hold it across the call boundary.
        int half = fullEncrypted.remaining() / 2;
        ByteBuffer firstHalf = sliceN(fullEncrypted, half);
        ByteBuffer secondHalf = sliceN(fullEncrypted, fullEncrypted.remaining());

        List<ByteBuffer> firstOut = serverBuffer.unwrap(firstHalf);
        assertTrue("partial record should yield no output", firstOut.isEmpty());

        List<ByteBuffer> secondOut = serverBuffer.unwrap(secondHalf);
        assertEquals(1, secondOut.size());
        assertArrayEquals(payload, toByteArray(secondOut.get(0)));
    }

    @Test
    public void unwrap_handlesMultipleRecordsInOneRead() throws Exception {
        EnginePair pair = handshake();
        SSLEngineBuffer serverBuffer = new SSLEngineBuffer(pair.server, mockConnection);

        byte[] a = "first".getBytes(StandardCharsets.UTF_8);
        byte[] b = "second".getBytes(StandardCharsets.UTF_8);
        ByteBuffer encA = clientEncrypt(pair.client, a);
        ByteBuffer encB = clientEncrypt(pair.client, b);

        // Concatenate two full records into one input buffer.
        ByteBuffer combined = ByteBuffer.allocate(encA.remaining() + encB.remaining());
        combined.put(encA);
        combined.put(encB);
        combined.flip();

        List<ByteBuffer> decrypted = serverBuffer.unwrap(combined);
        assertEquals(2, decrypted.size());
        assertArrayEquals(a, toByteArray(decrypted.get(0)));
        assertArrayEquals(b, toByteArray(decrypted.get(1)));
    }

    @Test
    public void wrap_roundTripsAcrossPeer() throws Exception {
        EnginePair pair = handshake();
        SSLEngineBuffer serverBuffer = new SSLEngineBuffer(pair.server, mockConnection);

        byte[] payload = "server-side write".getBytes(StandardCharsets.UTF_8);
        List<ByteBuffer> encrypted = serverBuffer.wrap(ByteBuffer.wrap(payload));

        // Concatenate the encrypted records and feed them into the peer client engine.
        int total = 0;
        for (ByteBuffer b : encrypted) total += b.remaining();
        ByteBuffer combined = ByteBuffer.allocate(total);
        for (ByteBuffer b : encrypted) combined.put(b);
        combined.flip();

        ByteBuffer plaintext = ByteBuffer.allocate(payload.length + 64);
        SSLEngineResult r = pair.client.unwrap(combined, plaintext);
        assertEquals(SSLEngineResult.Status.OK, r.getStatus());
        plaintext.flip();
        assertArrayEquals(payload, toByteArray(plaintext));
    }

    private static class EnginePair {
        final SSLEngine server;
        final SSLEngine client;
        EnginePair(SSLEngine s, SSLEngine c) { this.server = s; this.client = c; }
    }

    private EnginePair handshake() throws Exception {
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, new TrustManager[]{new TrustEverything()}, null);

        SSLEngine server = sslContext.createSSLEngine();
        server.setUseClientMode(false);
        SSLEngine client = clientCtx.createSSLEngine("localhost", 443);
        client.setUseClientMode(true);

        // Pin to TLS 1.2 — avoids TLS 1.3 post-handshake NewSessionTicket bookkeeping
        // that would desync the test driver and cause bad_record_mac on the next wrap.
        String[] tls12 = new String[]{"TLSv1.2"};
        server.setEnabledProtocols(tls12);
        client.setEnabledProtocols(tls12);

        int net = server.getSession().getPacketBufferSize();
        int app = server.getSession().getApplicationBufferSize();
        ByteBuffer cToS = ByteBuffer.allocate(net * 2);
        ByteBuffer sToC = ByteBuffer.allocate(net * 2);
        ByteBuffer clientApp = ByteBuffer.allocate(app);
        ByteBuffer serverApp = ByteBuffer.allocate(app);

        server.beginHandshake();
        client.beginHandshake();

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!isHandshakeDone(server) || !isHandshakeDone(client)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("handshake stuck (server=" + server.getHandshakeStatus()
                        + ", client=" + client.getHandshakeStatus() + ")");
            }
            stepHandshake(client, sToC, cToS, clientApp);
            stepHandshake(server, cToS, sToC, serverApp);
        }
        return new EnginePair(server, client);
    }

    private static boolean isHandshakeDone(SSLEngine e) {
        SSLEngineResult.HandshakeStatus s = e.getHandshakeStatus();
        return s == SSLEngineResult.HandshakeStatus.FINISHED
                || s == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    private static void stepHandshake(SSLEngine engine, ByteBuffer in, ByteBuffer out, ByteBuffer scratchApp)
            throws SSLException {
        SSLEngineResult.HandshakeStatus s = engine.getHandshakeStatus();
        switch (s) {
            case NEED_UNWRAP:
                in.flip();
                if (in.hasRemaining()) {
                    SSLEngineResult r = engine.unwrap(in, scratchApp);
                    in.compact();
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // Need more bytes — yield to peer.
                    }
                } else {
                    in.compact();
                }
                break;
            case NEED_WRAP:
                ByteBuffer empty = ByteBuffer.allocate(0);
                engine.wrap(empty, out);
                break;
            case NEED_TASK:
                Runnable t;
                while ((t = engine.getDelegatedTask()) != null) {
                    ForkJoinPool.commonPool().submit(t).join();
                }
                break;
            case FINISHED:
            case NOT_HANDSHAKING:
            default:
                break;
        }
    }

    private static ByteBuffer clientEncrypt(SSLEngine client, byte[] plaintext) throws SSLException {
        ByteBuffer src = ByteBuffer.wrap(plaintext);
        ByteBuffer net = ByteBuffer.allocate(client.getSession().getPacketBufferSize());
        SSLEngineResult r = client.wrap(src, net);
        assertEquals(SSLEngineResult.Status.OK, r.getStatus());
        net.flip();
        return net;
    }

    private static ByteBuffer sliceN(ByteBuffer src, int n) {
        ByteBuffer copy = ByteBuffer.allocate(n);
        for (int i = 0; i < n; i++) copy.put(src.get());
        copy.flip();
        return copy;
    }

    private static byte[] toByteArray(ByteBuffer b) {
        byte[] arr = new byte[b.remaining()];
        b.get(arr);
        return arr;
    }

    private static SSLContext loadServerContext() throws Exception {
        char[] storePass = "storepass".toCharArray();
        char[] keyPass = "keypass".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = new FileInputStream("./src/main/resources/server.jks")) {
            ks.load(in, storePass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{new TrustEverything()}, null);
        return ctx;
    }

    private static class TrustEverything implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
