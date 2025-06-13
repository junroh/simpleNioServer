package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
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
import java.nio.channels.SelectionKey;
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
        MockitoAnnotations.initMocks(this);

        when(mockConnectedSocket.getSocketChannel()).thenReturn(mockSocketChannel);
        when(mockConnectedSocket.tryReadLock()).thenReturn(true);
        when(mockConnectedSocket.isClosed()).thenReturn(false);

        readHandler = new SocketReadHandler(mockConnectedSocket, mockCompleteListener);
    }

    @Test
    public void testRun_SuccessfulRead() throws IOException {
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer b = invocation.getArgumentAt(0, ByteBuffer.class);
            byte[] testData = "TestData".getBytes("UTF-8");
            b.put(testData);
            return testData.length;
        }).thenReturn(-1);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel, atLeastOnce()).read(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(integerCaptor.capture(), byteBuffersCaptor.capture());

        assertEquals(Integer.valueOf("TestData".getBytes("UTF-8").length), integerCaptor.getValue());
        List<ByteBuffer> capturedBuffers = byteBuffersCaptor.getValue();
        assertEquals(1, capturedBuffers.size());

        ByteBuffer resultBuffer = capturedBuffers.get(0);
        byte[] data = new byte[resultBuffer.remaining()];
        resultBuffer.get(data);
        assertEquals("TestData", new String(data, "UTF-8"));

        verify(mockConnectedSocket).addInterestedOps(SelectionKey.OP_READ);
        verify(mockConnectedSocket).unLockRead();
    }

    @Test
    public void testRun_ReadReturnsEndOfStreamImmediately() throws IOException {
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        // Override the default isClosed() behavior from setUp for this specific test case.
        // After read() returns -1, readSocket() calls socket.close().
        // So, when run() checks socket.isClosed(), it should be true.
        when(mockConnectedSocket.isClosed()).thenReturn(true);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class)); // Verifies read was attempted
        verify(mockConnectedSocket).close(); // Verify that close was actually called
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList()); // This assertion should now pass
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_READ); // Because socket is closed
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
    public void testRun_WhenTryReadLockFalse_DoesNotProceed() throws IOException {
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
        // This test was the source of the original phantom error.
        // Ensure its method signature is correct and all Mockito setup is within @Before or the test itself.
        when(mockConnectedSocket.isClosed()).thenReturn(true);
        when(mockSocketChannel.read(any(ByteBuffer.class))).thenReturn(-1);

        readHandler.run();

        verify(mockConnectedSocket).tryReadLock();
        verify(mockSocketChannel).read(any(ByteBuffer.class));
        verify(mockConnectedSocket, atLeastOnce()).close();
        verify(mockCompleteListener, never()).onComplete(anyInt(), anyList());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_READ);
        verify(mockConnectedSocket).unLockRead();
    }
}
