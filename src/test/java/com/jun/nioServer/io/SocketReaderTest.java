package com.jun.nioServer.io;

import com.jun.nioServer.Connection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations; // Added this import that was missing in my prior thought block
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;

public class SocketReaderTest {

    @Mock private Connection mockConnection;
    @Mock private SocketChannel mockSocketChannel;
    @Mock private IoCallback mockCompleteListener;

    @Captor private ArgumentCaptor<List<ByteBuffer>> byteBuffersCaptor;
    @Captor private ArgumentCaptor<Integer> integerCaptor;
    @Captor private ArgumentCaptor<Exception> exceptionCaptor;

    private SocketReader readHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mockConnection.getSocketChannel()).thenReturn(mockSocketChannel);
        when(mockConnection.tryReadLock()).thenReturn(true);
        when(mockConnection.isClosed()).thenReturn(false);

        readHandler = new SocketReader(mockConnection, mockCompleteListener, 64 * 1024);
    }

    @Test
    public void testRun_SuccessfulRead() throws IOException {
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer b = invocation.getArgument(0, ByteBuffer.class);
            byte[] testData = "TestData".getBytes("UTF-8");
            b.put(testData);
            return testData.length;
        }).thenReturn(0);   // 0 = no more data right now (connection stays open), NOT -1 (EOF)

        readHandler.run();

        verify(mockConnection).tryReadLock();
        verify(mockSocketChannel, atLeastOnce()).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(integerCaptor.capture(), byteBuffersCaptor.capture());

        assertEquals(Integer.valueOf("TestData".getBytes("UTF-8").length), integerCaptor.getValue());
        List<ByteBuffer> capturedBuffers = byteBuffersCaptor.getValue();
        assertEquals(1, capturedBuffers.size());

        ByteBuffer resultBuffer = capturedBuffers.get(0);
        byte[] data = new byte[resultBuffer.remaining()];
        resultBuffer.get(data);
        assertEquals("TestData", new String(data, "UTF-8"));

        verify(mockConnection).requestRead();
        verify(mockConnection).unlockRead();
    }

    @Test
    public void testRun_ReadReturnsEndOfStreamImmediately() throws IOException {
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        // On EOF the reader no longer hard-closes (that would kill an in-flight response).
        // It marks the connection close-after-writes and delivers an (empty) completion so
        // the dispatcher can close once writes drain. isClosed() stays false (setUp default).
        readHandler.run();

        verify(mockConnection).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        verify(mockConnection, never()).close();              // must NOT hard-close on EOF
        verify(mockConnection).markCloseAfterWrites();         // graceful close on peer half-close
        verify(mockCompleteListener).onComplete(eq(0), anyList()); // delivered (no bytes)
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnection, never()).requestRead();         // peer done sending
        verify(mockConnection).unlockRead();
    }

    @Test
    public void testRun_ReadThrowsIOException() throws IOException {
        IOException testException = new IOException("Test read error");
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenThrow(testException);

        readHandler.run();

        verify(mockConnection).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onException(exceptionCaptor.capture());
        assertSame(testException, exceptionCaptor.getValue());
        verify(mockConnection, never()).requestRead();
        verify(mockConnection).unlockRead();
    }

    @Test
    public void testRun_WhenTryReadLockFalse_ReArmsAndReturns() throws IOException {
        when(mockConnection.tryReadLock()).thenReturn(false);

        readHandler.run();

        verify(mockConnection).tryReadLock();
        verify(mockSocketChannel, never()).read(any(ByteBuffer.class));
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnection, never()).unlockRead();
        // The reactor cleared OP_READ before submitting us; on a lock bounce we MUST re-arm it,
        // or the bytes that triggered this event are stranded and the peer hangs.
        verify(mockConnection).requestRead();
    }

    @Test
    public void testRun_ConnectionClosedDuringRead_SkipsCompletion() throws IOException {
        when(mockConnection.isClosed()).thenReturn(true);
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        readHandler.run();

        verify(mockConnection).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        // Connection already closed → reader delivers nothing and re-arms nothing.
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnection, never()).requestRead();
        verify(mockConnection, never()).markCloseAfterWrites();
        verify(mockConnection).unlockRead();
    }

    @Test
    public void testRun_StopsAtReadBudget() throws IOException {
        // Budget: 8K. Each ByteBuffer the handler allocates is 4K, and we always
        // fill it completely. After 2 reads (8K total) the handler should stop and
        // re-arm OP_READ rather than continuing to drain the kernel buffer.
        SocketReader bounded = new SocketReader(mockConnection, mockCompleteListener, 8 * 1024);

        when(mockSocketChannel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer b = invocation.getArgument(0, ByteBuffer.class);
            int filled = b.remaining();
            b.position(b.limit());
            return filled;
        });

        bounded.run();

        verify(mockConnection).tryReadLock();
        // The handler reads 4K twice to fill two 4K buffers; the inner loop's hasRemaining()
        // breaks each time the buffer is full. So channel.read is called once per buffer.
        verify(mockSocketChannel, times(2)).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(8 * 1024), byteBuffersCaptor.capture());
        assertEquals(2, byteBuffersCaptor.getValue().size());
        verify(mockConnection).requestRead();
        verify(mockConnection).unlockRead();
    }
}
