package com.jun.nioServer;

import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

/**
 * Reproduces HIGH-2: when async dispatch is enabled, two handlers for the SAME connection
 * run concurrently and both call {@link Connection#sendResponse}/
 * {@link Connection#prepareBuffersForWriting}. That method drains {@code writeMsgs} and
 * appends each message's chunks to {@code writeBuffers} with no per-connection
 * serialization, so two responses' bytes interleave in {@code writeBuffers} — the client
 * on a keep-alive connection receives a corrupted, byte-interleaved stream.
 *
 * <p>The test queues one all-'A' message and one all-'B' message, releases two threads
 * into {@code prepareBuffersForWriting} simultaneously via a {@link CyclicBarrier}, then
 * checks that the resulting buffer order is NOT interleaved: a correct result is every 'A'
 * buffer contiguous and every 'B' buffer contiguous (at most one A↔B transition). Repeated
 * many times to make the race fire deterministically.
 */
public class ConnectionWriteRaceTest {

    private static final int CHUNKS_PER_MESSAGE = 64;
    private static final int ITERATIONS = 500;

    private SocketChannel channel;

    @Before
    public void setUp() throws Exception {
        // Unconnected real channel: the Connection constructor only calls
        // configureBlocking(false) on it, which works without a connection.
        channel = SocketChannel.open();
    }

    @After
    public void tearDown() throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    @Test
    public void concurrentSendResponse_doesNotInterleaveResponseBytes() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int iter = 0; iter < ITERATIONS; iter++) {
                Connection conn = new Connection(
                        iter, channel, mock(MessageReader.class), mock(IOReactor.class));

                Message msgA = messageOf(conn, (byte) 'A');
                Message msgB = messageOf(conn, (byte) 'B');

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> fa = pool.submit(assembler(conn, msgA, barrier));
                Future<?> fb = pool.submit(assembler(conn, msgB, barrier));
                fa.get(5, TimeUnit.SECONDS);
                fb.get(5, TimeUnit.SECONDS);

                String pattern = drainPattern(conn);
                int transitions = countTransitions(pattern);
                if (transitions > 1) {
                    fail("Response bytes interleaved on iteration " + iter
                            + " (" + transitions + " A/B transitions, expected <= 1).\n"
                            + "writeBuffers order: " + pattern);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** A Connection task that queues its message then assembles buffers, synced on the barrier. */
    private static Runnable assembler(Connection conn, Message msg, CyclicBarrier barrier) {
        return () -> {
            try {
                conn.addWriteReadyMsg(msg);
                barrier.await(5, TimeUnit.SECONDS);   // both messages queued before either assembles
                conn.prepareBuffersForWriting();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private static Message messageOf(Connection conn, byte fill) {
        Message m = new Message(conn);
        for (int i = 0; i < CHUNKS_PER_MESSAGE; i++) {
            m.writeToMessage(new byte[]{fill});
        }
        return m;
    }

    /** Drain writeBuffers into the sequence of 'A'/'B' bytes, one char per buffer. */
    private static String drainPattern(Connection conn) {
        List<ByteBuffer> buffers = conn.drainWriteBuffers();
        StringBuilder sb = new StringBuilder(buffers.size());
        for (ByteBuffer b : buffers) {
            sb.append((char) b.get(b.position()));
        }
        return sb.toString();
    }

    /** Number of points where the message source flips (e.g. "AAABBB"=1, "ABAB"=3). */
    private static int countTransitions(String pattern) {
        int transitions = 0;
        for (int i = 1; i < pattern.length(); i++) {
            if (pattern.charAt(i) != pattern.charAt(i - 1)) {
                transitions++;
            }
        }
        return transitions;
    }
}
