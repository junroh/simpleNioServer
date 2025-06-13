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
                ByteBuffer b = invocation.getArgumentAt(0, ByteBuffer.class);
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

        int firstWriteAmount = 5; // Simulate writing 5 bytes initially

        when(mockConnectedSocket.getWritebuffers()).thenReturn(writeBuffers);

        // Simulate first write succeeds partially, subsequent writes in this cycle return 0
        when(mockSocketChannel.write(buffer1))
            .thenAnswer(invocation -> { // First call
                ByteBuffer b = invocation.getArgumentAt(0, ByteBuffer.class);
                b.position(b.position() + firstWriteAmount);
                return firstWriteAmount;
            })
            .thenReturn(0); // Subsequent calls in the same internal loop of SocketWriteHandler.write()


        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        // The internal write loop in SocketWriteHandler would call channel.write() once (returns 5),
        // then again (mock returns 0), inner loop terminates.
        verify(mockSocketChannel, times(2)).write(buffer1);
        verify(mockCompleteListener).onComplete(eq(firstWriteAmount), any());

        // Buffer1 still has data (original_data_length - firstWriteAmount bytes remaining)
        assertTrue(buffer1.hasRemaining());
        // assertEquals("PartialData".getBytes("UTF-8").length - firstWriteAmount, buffer1.remaining()); // More precise check

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

        try {
            writeHandler.run();
        } catch (Exception e) {
            // This test expects an exception to be caught by the handler's listener,
            // not propagated from run(). If run() threw, it's a problem with run's catch block.
            fail("Exception should have been handled by SocketWriteHandler.run() and passed to listener: " + e.getMessage());
        }

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockSocketChannel).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onException(exceptionCaptor.capture());
        assertSame(testException, exceptionCaptor.getValue());
        verify(mockConnectedSocket).unLockWrite();
    }

    @Test
    public void testRun_NoDataToWrite() throws IOException { // Added throws IOException back
        when(mockConnectedSocket.getWritebuffers()).thenReturn(new ArrayList<>());

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockConnectedSocket).getWritebuffers();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(0), eq(null));
        verify(mockConnectedSocket).unLockWrite();
    }

    @Test
    public void testRun_WhenTryWriteLockFalse_DoesNotProceed() throws IOException { // Added throws IOException
        when(mockConnectedSocket.tryWriteLock()).thenReturn(false);

        writeHandler.run();

        verify(mockConnectedSocket).tryWriteLock();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener, never()).onComplete(anyInt(), any()); // any() for list
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnectedSocket, never()).unLockWrite();
    }
}
