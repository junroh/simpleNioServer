package com.jun.nioServer.io;

import com.jun.nioServer.Connection;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public class SocketWriterTest {

    @Mock private Connection mockConnection;
    @Mock private SocketChannel mockSocketChannel;
    @Mock private IoCallback mockCompleteListener;

    @Captor private ArgumentCaptor<Integer> integerCaptor;
    @Captor private ArgumentCaptor<Exception> exceptionCaptor;

    private SocketWriter writeHandler;

    @Before
    public void setUp() {
        org.mockito.MockitoAnnotations.initMocks(this);

        when(mockConnection.getSocketChannel()).thenReturn(mockSocketChannel);
        when(mockConnection.tryWriteLock()).thenReturn(true);

        writeHandler = new SocketWriter(mockConnection, mockCompleteListener);
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

        when(mockConnection.drainWriteBuffers()).thenReturn(writeBuffers);

        when(mockSocketChannel.write(any(ByteBuffer.class)))
            .thenAnswer(invocation -> {
                ByteBuffer b = invocation.getArgument(0, ByteBuffer.class);
                int r = b.remaining();
                b.position(b.limit());
                return r;
            });

        writeHandler.run();

        verify(mockConnection).tryWriteLock();
        verify(mockConnection).drainWriteBuffers();
        verify(mockSocketChannel, times(2)).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(totalSize), eq(null));
        verify(mockConnection).unlockWrite();
        // All data written → no need to re-arm OP_WRITE.
        verify(mockConnection, never()).requestWrite();
    }

    @Test
    public void testRun_PartialWrite_RequestsAnotherWrite() throws IOException {
        ByteBuffer buffer1 = ByteBuffer.allocate(20);
        buffer1.put("PartialData".getBytes("UTF-8"));
        buffer1.flip();

        List<ByteBuffer> writeBuffers = new ArrayList<>();
        writeBuffers.add(buffer1);

        int firstWriteAmount = 5;

        when(mockConnection.drainWriteBuffers()).thenReturn(writeBuffers);

        when(mockSocketChannel.write(buffer1))
            .thenAnswer(invocation -> {
                ByteBuffer b = invocation.getArgument(0, ByteBuffer.class);
                b.position(b.position() + firstWriteAmount);
                return firstWriteAmount;
            })
            .thenReturn(0);


        writeHandler.run();

        verify(mockConnection).tryWriteLock();
        verify(mockSocketChannel, times(2)).write(buffer1);
        verify(mockCompleteListener).onComplete(eq(firstWriteAmount), any());

        assertTrue(buffer1.hasRemaining());

        // Buffer not fully drained → ask the reactor to write again. No selector fiddling here.
        verify(mockConnection).requestWrite();
        verify(mockConnection).unlockWrite();
    }


    @Test
    public void testRun_WriteThrowsIOException() throws IOException {
        List<ByteBuffer> writeBuffers = new ArrayList<>();
        writeBuffers.add(ByteBuffer.wrap("TestData".getBytes("UTF-8")));
        when(mockConnection.drainWriteBuffers()).thenReturn(writeBuffers);

        IOException testException = new IOException("Test write error");
        when(mockSocketChannel.write(any(ByteBuffer.class))).thenThrow(testException);

        try {
            writeHandler.run();
        } catch (Exception e) {
            fail("Exception should have been handled by SocketWriter.run() and passed to listener: " + e.getMessage());
        }

        verify(mockConnection).tryWriteLock();
        verify(mockSocketChannel).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onException(exceptionCaptor.capture());
        assertSame(testException, exceptionCaptor.getValue());
        verify(mockConnection).unlockWrite();
    }

    @Test
    public void testRun_NoDataToWrite() throws IOException {
        when(mockConnection.drainWriteBuffers()).thenReturn(new ArrayList<>());

        writeHandler.run();

        verify(mockConnection).tryWriteLock();
        verify(mockConnection).drainWriteBuffers();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener).onComplete(eq(0), eq(null));
        verify(mockConnection).unlockWrite();
    }

    @Test
    public void testRun_WhenTryWriteLockFalse_DoesNotProceed() throws IOException {
        when(mockConnection.tryWriteLock()).thenReturn(false);

        writeHandler.run();

        verify(mockConnection).tryWriteLock();
        verify(mockSocketChannel, never()).write(any(ByteBuffer.class));
        verify(mockCompleteListener, never()).onComplete(anyInt(), any());
        verify(mockCompleteListener, never()).onException(any(Exception.class));
        verify(mockConnection, never()).unlockWrite();
    }
}
