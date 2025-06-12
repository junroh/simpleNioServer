package com.jun.nioServer;

// Corrected import for NioMessageHandler
import com.jun.http.NioMessageHandler;
import com.jun.nioServer.handler.MsgHandler; // For constructor, will be mocked (actually not, IOReactor creates it)
import com.jun.nioServer.msg.IMessageReaderFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class IOReactorTest {

    private IOReactor ioReactor;
    private Selector mockSelector;
    private ExecutorService mockReaderPool;
    private ExecutorService mockWriterPool;
    // These are not directly injected into IOReactor but created by it for MsgHandler.
    // So, we don't mock them at IOReactor's constructor level.
    // private IMessageReaderFactory mockReaderFactory;
    // private com.jun.http.NioMessageHandler mockNioMessageHandler;

    @Before
    public void setUp() throws IOException {
        mockSelector = mock(Selector.class);
        mockReaderPool = mock(ExecutorService.class);
        mockWriterPool = mock(ExecutorService.class);

        // IOReactor's constructor as of subtask 16:
        // public IOReactor(Selector givenSelector, ExecutorService readerPool, ExecutorService writerPool)
        // Inside, it does:
        // IMessageReaderFactory readerFactory = new HttpMessageReaderFactory();
        // NioMessageHandler nioMessageHandler = new SimpleNioMessageHandler();
        // this.msgHandler = new MsgHandler(readerFactory, nioMessageHandler);
        // So, no need to mock readerFactory or nioMessageHandler for IOReactor's construction.
        ioReactor = new IOReactor(mockSelector, mockReaderPool, mockWriterPool);
    }

    @Test
    public void testRegNewSocket_RegistersChannelAndConfiguresKey() throws IOException {
        SocketChannel mockNewSocketChannel = mock(SocketChannel.class);
        SSLContext mockSslContext = null; // Test non-SSL case first
        int socketId = 123;

        SelectionKey mockNewSelectionKey = mock(SelectionKey.class);

        // When newSocketChannel.register(mockSelector, 0) is called, return our mock key
        when(mockNewSocketChannel.register(eq(mockSelector), anyInt())).thenReturn(mockNewSelectionKey);

        ioReactor.regNewSocket(mockNewSocketChannel, socketId, mockSslContext);

        // Verify that the selector was woken up
        verify(mockSelector).wakeup();

        // Verify that the channel was registered with interestOps 0 initially
        verify(mockNewSocketChannel).register(mockSelector, 0);

        // Capture the ConnectedSocket created and passed to mockNewSelectionKey.attach()
        ArgumentCaptor<Object> attachmentCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockNewSelectionKey).attach(attachmentCaptor.capture());
        assertTrue(attachmentCaptor.getValue() instanceof ConnectedSocket);
        ConnectedSocket attachedSocket = (ConnectedSocket) attachmentCaptor.getValue();
        assertEquals(socketId, attachedSocket.getSocketId());
        assertSame(mockNewSocketChannel, attachedSocket.getSocketChannel()); // Check correct channel was used

        verify(mockNewSelectionKey).interestOps(SelectionKey.OP_READ);
        verify(mockNewSocketChannel).configureBlocking(false); // From ConnectedSocket constructor
    }

    @Test
    public void testRegNewSocket_WithSslContext() throws IOException {
        SocketChannel mockNewSocketChannel = mock(SocketChannel.class);
        SSLContext mockSslContext = mock(SSLContext.class); // Mock SSLContext
        int socketId = 456;
        SelectionKey mockNewSelectionKey = mock(SelectionKey.class);
        when(mockNewSocketChannel.register(eq(mockSelector), anyInt())).thenReturn(mockNewSelectionKey);

        // ConnectedSocket constructor will be called with this mockSslContext.
        // We assume that the constructor itself (and SSLEngineBuffer init)
        // can handle a mocked SSLContext without throwing an error during this test,
        // or that those specific interactions are not what this IOReactor test is focusing on.
        // The main goal here is to see IOReactor pass it.
        // If SSLEngineBuffer.init() is called in ConnectedSocket constructor and fails with a mock,
        // this test might need ConnectedSocket to be more mockable or SSLEngineBuffer injected.
        // For now, let's assume it passes through.

        ioReactor.regNewSocket(mockNewSocketChannel, socketId, mockSslContext);

        verify(mockSelector).wakeup();
        verify(mockNewSocketChannel).register(mockSelector, 0);

        ArgumentCaptor<Object> attachmentCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockNewSelectionKey).attach(attachmentCaptor.capture());
        assertTrue(attachmentCaptor.getValue() instanceof ConnectedSocket);
        ConnectedSocket capturedCs = (ConnectedSocket) attachmentCaptor.getValue();

        // We are testing IOReactor. The fact that it creates a ConnectedSocket
        // which *would* use the SSLContext is the extent of this test's concern for SSL here.
        // Deeper SSL logic is for ConnectedSocketTest or SSLEngineBufferTest.
        assertNotNull(capturedCs);
        // To really confirm SSLContext was "used", we'd need to inspect capturedCs,
        // e.g. if it had a method like "isSsl()" or if its SSLEngineBuffer was non-null.
        // This is beyond simple mocking for IOReactorTest.
    }

    @Test(expected = IOException.class)
    public void testRegNewSocket_RegisterThrowsIOException() throws IOException {
        SocketChannel mockNewSocketChannel = mock(SocketChannel.class);
        int socketId = 789;
        when(mockNewSocketChannel.register(eq(mockSelector), anyInt())).thenThrow(new IOException("Test register failed"));

        ioReactor.regNewSocket(mockNewSocketChannel, socketId, null);
        // Expect IOException to be propagated
    }
}
