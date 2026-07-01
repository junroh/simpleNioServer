package com.jun.nioServer;

import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageReader;
import org.junit.After;
import org.junit.Test;

import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link Connection}'s single graceful-close decision ({@link Connection#maybeClose()}
 * gated by the in-flight counter). Uses a real unconnected {@link SocketChannel} (so the close
 * actually flips {@code isOpen()}) with a mocked reactor/reader. No socket is bound.
 */
public class ConnectionTest {

    private SocketChannel channel;

    @After
    public void tearDown() throws Exception {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
    }

    private Connection newConnection() throws Exception {
        channel = SocketChannel.open();   // open but unconnected — no bind/connect
        return new Connection(1, channel, mock(MessageReader.class), mock(IOReactor.class));
    }

    @Test
    public void doesNotCloseWhileWorkInFlight_thenClosesWhenLastLeaves() throws Exception {
        Connection conn = newConnection();

        conn.enterInFlight();
        conn.markCloseAfterWrites();          // close requested, but inFlight > 0
        assertTrue("must not close while a response may still be produced", channel.isOpen());

        conn.leaveInFlight();                 // last unit of work → graceful close fires
        assertFalse("must close once the last in-flight unit leaves", channel.isOpen());
    }

    @Test
    public void neverClosesWithoutACloseRequest() throws Exception {
        Connection conn = newConnection();

        conn.enterInFlight();
        conn.leaveInFlight();                 // no close requested → keep-alive stays open
        assertTrue(channel.isOpen());
    }

    @Test
    public void waitsForPendingWritesBeforeClosing() throws Exception {
        Connection conn = newConnection();

        Message msg = new Message(conn);
        msg.writeToMessage("data".getBytes(StandardCharsets.UTF_8));
        conn.addWriteReadyMsg(msg);
        conn.prepareBuffersForWriting();      // bytes now queued in writeBuffers

        conn.markCloseAfterWrites();          // close requested, but bytes remain
        assertTrue("must not close while bytes are still queued", channel.isOpen());

        conn.drainWriteBuffers();             // writer drained everything
        conn.maybeClose();
        assertFalse("closes once writes have drained", channel.isOpen());
    }
}
