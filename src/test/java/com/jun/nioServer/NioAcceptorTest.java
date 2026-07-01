package com.jun.nioServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

/**
 * Constructor-validation tests for {@link NioAcceptor}. The accept loop is exercised by the
 * {@link NioServerServiceTest} integration test rather than mocked here, since
 * {@link ServerSocketChannel} cannot be reliably mocked under Mockito 1.x on modern JDKs.
 */
public class NioAcceptorTest {

    private ServerSocketChannel serverSocketChannel;
    private ExecutorService connectionRegistrarExecutor;
    private IOReactor[] reactors;

    @Before
    public void setUp() throws IOException {
        serverSocketChannel = ServerSocketChannel.open();
        try {
            serverSocketChannel.bind(new InetSocketAddress("localhost", 0));
        } catch (IOException e) {
            // Sandboxes / locked-down CI may forbid binds; skip the whole class in that case.
            serverSocketChannel.close();
            org.junit.Assume.assumeNoException("environment forbids bind()", e);
        }
        connectionRegistrarExecutor = Executors.newSingleThreadExecutor();
        reactors = new IOReactor[]{ mock(IOReactor.class) };
    }

    @After
    public void tearDown() throws IOException {
        if (serverSocketChannel != null && serverSocketChannel.isOpen()) {
            serverSocketChannel.close();
        }
        if (connectionRegistrarExecutor != null) {
            connectionRegistrarExecutor.shutdownNow();
        }
    }

    @Test
    public void constructor_rejectsNullServerSocketChannel() throws IOException {
        try {
            new NioAcceptor(null, connectionRegistrarExecutor, reactors, null, false, false, null, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void constructor_rejectsNullExecutor() throws IOException {
        try {
            new NioAcceptor(serverSocketChannel, null, reactors, null, false, false, null, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void constructor_rejectsNullReactors() throws IOException {
        try {
            new NioAcceptor(serverSocketChannel, connectionRegistrarExecutor, null, null, false, false, null, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void constructor_rejectsEmptyReactors() throws IOException {
        try {
            new NioAcceptor(serverSocketChannel, connectionRegistrarExecutor, new IOReactor[0], null, false, false, null, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void constructor_acceptsBlockingMode() throws IOException {
        // Should construct without throwing.
        NioAcceptor a = new NioAcceptor(serverSocketChannel, connectionRegistrarExecutor, reactors, null, false, false, null, 0);
        a.stop();
    }
}
