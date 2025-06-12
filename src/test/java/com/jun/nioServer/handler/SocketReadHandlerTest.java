package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock; // Import Mock annotation
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey; // Added for OP_READ
import java.nio.channels.SocketChannel;
import java.util.List;

public class SocketReadHandlerTest {

    @Mock private ConnectedSocket mockConnectedSocket;
    @Mock private SocketChannel mockSocketChannel;
    @Mock private OnCompleteListener mockCompleteListener;

    @Captor private ArgumentCaptor<List<ByteBuffer>> byteBuffersCaptor;
    @Captor private ArgumentCaptor<Integer> integerCaptor;
    @Captor private ArgumentCaptor<Exception> exceptionCaptor;

    private SocketReadHandler readHandler;

    @Before
    public void setUp() {
        org.mockito.MockitoAnnotations.initMocks(this);

        when(mockConnectedSocket.getSocketChannel()).thenReturn(mockSocketChannel);
        when(mockConnectedSocket.tryReadLock()).thenReturn(true);
        // Default behavior for isClosed, can be overridden in specific tests
        when(mockConnectedSocket.isClosed()).thenReturn(false);

        readHandler = new SocketReadHandler(mockConnectedSocket, mockCompleteListener);
    }

    @Test
    public void testRun_SuccessfulRead() throws IOException {
        // Simulate SocketChannel.read() populating the buffer
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer b = invocation.getArgument(0);
            byte[] testData = "TestData".getBytes("UTF-8");
            b.put(testData);
            return testData.length;
        }).thenReturn(-1); // Second call, return -1 to indicate end of stream / stop loop

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel, atLeastOnce()).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(integerCaptor.capture(), byteBuffersCaptor.capture());

        assertEquals(Integer.valueOf("TestData".getBytes("UTF-8").length), integerCaptor.getValue());
        List<ByteBuffer> capturedBuffers = byteBuffersCaptor.getValue();
        assertEquals(1, capturedBuffers.size());

        ByteBuffer resultBuffer = capturedBuffers.get(0);
        // The buffer was flipped by SocketReadHandler before adding to list, if readbytes > 0
        // In the handler: if (readbytes > 0) { readByteBuffer.flip(); socketDatas.add(readByteBuffer); }
        // So, no need to flip here for reading.
        byte[] data = new byte[resultBuffer.remaining()];
        resultBuffer.get(data);
        assertEquals("TestData", new String(data, "UTF-8"));

        // Verify that OP_READ is re-interested if socket is not closed after read
        verify(mockConnectedSocket).addInterestedOps(SelectionKey.OP_READ);
        verify(mockConnectedSocket).unLockRead();
    }

    @Test
    public void testRun_ReadReturnsEndOfStreamImmediately() throws IOException {
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        // The onComplete is called with totBytes. If first read is -1, totBytes remains 0.
        // SocketReadHandler logic: totBytes += readbytes; if (readbytes > 0) { socketDatas.add }
        // If readbytes is -1, totBytes will be -1.
        // The listener.onComplete(totBytes, socketDatas) will be called.
        // If bytesRead is -1, socket.close() is called.
        // Then, if !socket.isClosed() (which it now is), onComplete and addInterestedOps are skipped.
        // This needs careful check against SocketReadHandler logic.

        // Current SocketReadHandler logic:
        // do { ... readbytes = readSocket(...); if (readbytes > 0) { socketDatas.add... } totBytes += readbytes; } while (readbytes > 0);
        // if (!socket.isClosed()) { listener.onComplete(totBytes, socketDatas); socket.addInterestedOps(SelectionKey.OP_READ); }
        // readSocket() calls socket.close() if bytesRead == -1.
        // So, if first read is -1: totBytes = -1. socket.isClosed() becomes true.
        // Thus, listener.onComplete is NOT called. onException is not called.

        verify(mockConnectedSocket).close(); // Because read returned -1
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList()); // Not called because socket is now closed
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_READ); // Not called
        verify(mockConnectedSocket).unLockRead();
    }

    @Test
    public void testRun_ReadThrowsIOException() throws IOException {
        IOException testException = new IOException("Test read error");
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenThrow(testException);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onException(exceptionCaptor.capture());
        assertSame(testException, exceptionCaptor.getValue());
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_READ);
        verify(mockConnectedSocket).unLockRead();
    }

    @Test
    public void testRun_WhenTryReadLockFalse_DoesNotProceed() {
        when(mockConnectedSocket.tryReadLock()).thenReturn(false);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel, never()).read(any(ByteBuffer.class));
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).unLockRead();
    }

    @Test
    public void testRun_SocketIsAlreadyClosed_WhenCheckedBeforeReadLogic() throws IOException {
        // This test assumes tryReadLock() is true, but isClosed() is checked before detailed read logic.
        // SocketReadHandler: run -> tryReadLock -> core logic. isClosed() is checked *inside* readSocket and *after* read loop.
        // If socket is already closed, read() might behave differently (e.g. throw AsynchronousCloseException or return -1).
        // Let's simulate read() returning -1 because socket is perceived as closed by the channel.
        when(mockConnectedSocket.isClosed()).thenReturn(true); // Socket is initially closed
        // If isClosed() is true when readSocket is called, it might not even attempt a read, or read returns -1.
        // Let's assume SocketChannel.read() on an already closed (but not yet by this handler) socket returns -1.
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);


        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        // readSocket is called, which reads from mockSocketChannel
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        // Inside readSocket, if bytesRead == -1, socket.close() is called.
        // It might be called again even if already closed, which is fine.
        verify(mockConnectedSocket, atLeastOnce()).close();

        // After the read loop, if (!socket.isClosed()) { listener.onComplete ... }
        // Since socket.isClosed() is now true (or was already true and read reinforced it),
        // onComplete should not be called.
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_READ);
        verify(mockConnectedSocket).unLockRead();
    }
}
