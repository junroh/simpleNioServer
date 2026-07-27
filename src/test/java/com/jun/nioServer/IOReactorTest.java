package com.jun.nioServer;

import com.jun.nioServer.message.MessageDispatcher;
import com.jun.nioServer.message.MessageReaderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.channels.Selector;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link IOReactor}'s wiring and lifecycle. The selector loop itself is
 * exercised by the integration test in {@link NioServerServiceTest}; here we only verify
 * constructor validation and the start/shutdown contract.
 */
public class IOReactorTest {

    private static final int DEFAULT_READ_BUDGET = 64 * 1024;
    private static final int DEFAULT_WRITE_BUDGET = 64 * 1024;

    private Selector selector;
    private MessageDispatcher messageDispatcher;
    private MessageReaderFactory readerFactory;
    private ExecutorService ioPool;

    @Before
    public void setUp() throws Exception {
        selector = Selector.open();
        messageDispatcher = mock(MessageDispatcher.class);
        readerFactory = mock(MessageReaderFactory.class);
        ioPool = mock(ExecutorService.class);
    }

    @After
    public void tearDown() throws Exception {
        if (selector != null && selector.isOpen()) {
            selector.close();
        }
    }

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullSelector() {
        new IOReactor(null, messageDispatcher, readerFactory, ioPool, DEFAULT_READ_BUDGET, DEFAULT_WRITE_BUDGET);
    }

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullMessageDispatcher() {
        new IOReactor(selector, null, readerFactory, ioPool, DEFAULT_READ_BUDGET, DEFAULT_WRITE_BUDGET);
    }

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullReaderFactory() {
        new IOReactor(selector, messageDispatcher, null, ioPool, DEFAULT_READ_BUDGET, DEFAULT_WRITE_BUDGET);
    }

    @Test(expected = NullPointerException.class)
    public void constructor_rejectsNullPool() {
        new IOReactor(selector, messageDispatcher, readerFactory, null, DEFAULT_READ_BUDGET, DEFAULT_WRITE_BUDGET);
    }

    @Test
    public void constructor_rejectsNonPositiveReadBudget() {
        try {
            new IOReactor(selector, messageDispatcher, readerFactory, ioPool, 0, DEFAULT_WRITE_BUDGET);
            fail("expected IllegalArgumentException for zero budget");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            new IOReactor(selector, messageDispatcher, readerFactory, ioPool, -1, DEFAULT_WRITE_BUDGET);
            fail("expected IllegalArgumentException for negative budget");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void constructor_rejectsNonPositiveWriteBudget() {
        try {
            new IOReactor(selector, messageDispatcher, readerFactory, ioPool, DEFAULT_READ_BUDGET, 0);
            fail("expected IllegalArgumentException for zero write budget");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            new IOReactor(selector, messageDispatcher, readerFactory, ioPool, DEFAULT_READ_BUDGET, -1);
            fail("expected IllegalArgumentException for negative write budget");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void stop_shutsDownIoPool() {
        IOReactor reactor = new IOReactor(selector, messageDispatcher, readerFactory,
                ioPool, DEFAULT_READ_BUDGET, DEFAULT_WRITE_BUDGET);
        reactor.stop();
        verify(ioPool, times(1)).shutdown();
    }
}
