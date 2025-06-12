package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock; // Import Mock
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector; // Import Selector
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public class SocketWriteHandlerTest {

    @Mock private ConnectedSocket mockConnectedSocket;
    @Mock private SocketChannel mockSocketChannel;
    @Mock private OnCompleteListener mockCompleteListener;
    @Mock private SelectionKey mockSelectionKey; // For OP_WRITE re-registration test
    @Mock private Selector mockSelector; // For selector wakeup test

    @Captor private ArgumentCaptor<Integer> integerCaptor;
    @Captor private ArgumentCaptor<Exception> exceptionCaptor;

    private SocketWriteHandler writeHandler;

    @Before
    public void setUp() {
        org.mockito.MockitoAnnotations.initMocks(this);

        when(mockConnectedSocket.getSocketChannel()).thenReturn(mockSocketChannel);
        when(mockConnectedSocket.tryWriteLock()).thenReturn(true);

        // Setup for OP_WRITE re-registration
        when(mockConnectedSocket.getKey()).thenReturn(mockSelectionKey);
        when(mockSelectionKey.selector()).thenReturn(mockSelector);

        writeHandler = new SocketWriteHandler(mockConnectedSocket, mockCompleteListener);
    }

    @Test
    public void testRun_SuccessfulWrite_AllDataWritten() throws IOException {
        ByteBuffer buffer1 = ByteBuffer.wrap("Data1".getBytes("UTF-8"));
        ByteBuffer buffer2 = ByteBuffer.wrap("MoreData".getBytes("UTF-8"));
        List<ByteBuffer> writeBuffers = new ArrayList<>();
        writeBuffers.add(buffer1);
        writeBuffers.add(buffer2);

        int buffer1Size = buffer1.remaining();
        int buffer2Size = buffer2.remaining();
        int totalSize = buffer1Size + buffer2Size;

        // SocketWriteHandler gets buffers once, then iterates.
        when(mockConnectedSocket.getWritebuffers()).thenReturn(writeBuffers);

        when(mockSocketChannel.write(any(ByteBuffer.class)))
            .thenAnswer(invocation -> {
                ByteBuffer b = invocation.getArgument(0);
                int r = b.remaining();
                b.position(b.limit());
                return r;
            }); // Second call for buffer2 will also use this due to any(ByteBuffer.class)

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockConnectedSocket).getWritebuffers();
        verify(mockSocketChannel, times(2)).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(totalSize), eq(null));
        verify(mockConnectedSocket).unLockWrite();
        // OP_WRITE should not be re-registered if all data is written and list becomes empty
        verify(mockConnectedSocket, never()).addInterestedOps(SelectionKey.OP_WRITE);
        verify(mockSelector, never()).wakeup();
    }

    @Test
    public void testRun_PartialWrite_ReRegistersOpWrite() throws IOException {
        ByteBuffer buffer1 = ByteBuffer.allocate(20); // Allocate more than needed
        buffer1.put("PartialData".getBytes("UTF-8"));
        buffer1.flip(); // Prepare for reading by channel.write

        List<ByteBuffer> writeBuffers = new ArrayList<>();
        writeBuffers.add(buffer1);

        int writtenAmount = 5;

        when(mockConnectedSocket.getWritebuffers()).thenReturn(writeBuffers);
        when(mockSocketChannel.write(any(ByteBuffer.class))).thenAnswer(invocation -> {
            ByteBuffer b = invocation.getArgument(0);
            // Simulate partial write by advancing position less than limit
            b.position(b.position() + writtenAmount);
            return writtenAmount;
        });

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockSocketChannel).write(buffer1); // Verify with the specific buffer
        verify(mockCompleteListener).onComplete(eq(writtenAmount), any());

        // Because the buffer was not fully written, it remains in `readyBuffers` (local to run method).
        // The `if(!readyBuffers.isEmpty())` check in SocketWriteHandler should then trigger re-registration.
        verify(mockConnectedSocket).addInterestedOps(SelectionKey.OP_WRITE);
        verify(mockSelector).wakeup(); // Check selector wakeup
        verify(mockConnectedSocket).unLockWrite();
    }


    @Test
    public void testRun_WriteThrowsIOException() throws IOException {
        List<ByteBuffer> writeBuffers = new ArrayList<>();
        writeBuffers.add(ByteBuffer.wrap("TestData".getBytes("UTF-8")));
        when(mockConnectedSocket.getWritebuffers()).thenReturn(writeBuffers);

        IOException testException = new IOException("Test write error");
        when(mockSocketChannel.write(any(ByteBuffer.class))).thenThrow(testException);

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockSocketChannel).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onException(exceptionCaptor.capture());
        assertSame(testException, exceptionCaptor.getValue());
        verify(mockConnectedSocket).unLockWrite();
    }

    @Test
    public void testRun_NoDataToWrite() {
        when(mockConnectedSocket.getWritebuffers()).thenReturn(new ArrayList<>());

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockConnectedSocket).getWritebuffers();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(0), eq(null));
        verify(mockConnectedSocket).unLockWrite();
    }

    @Test
    public void testRun_WhenTryWriteLockFalse_DoesNotProceed() {
        when(mockConnectedSocket.tryWriteLock()).thenReturn(false);

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener, never()).onComplete(anyInt(), any()); // any() for list
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).unLockWrite();
    }
}
