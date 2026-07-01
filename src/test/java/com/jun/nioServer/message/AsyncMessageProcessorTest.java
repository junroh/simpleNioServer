package com.jun.nioServer.message;

import com.jun.nioServer.Connection;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AsyncMessageProcessor}. Most cases use a small recording delegate;
 * the lifecycle cases mock {@link Connection} to assert the close/in-flight side-effects.
 */
public class AsyncMessageProcessorTest {

    private AsyncMessageProcessor wrapper;

    @After
    public void tearDown() {
        if (wrapper != null) {
            wrapper.shutdown(2, TimeUnit.SECONDS);
        }
    }

    /** Delegate that records which thread invoked it. */
    private static final class RecordingProcessor implements MessageProcessor {
        final AtomicReference<Thread> callerThread = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch invoked;

        RecordingProcessor(int expectedCalls) {
            this.invoked = new CountDownLatch(expectedCalls);
        }

        @Override
        public void processMessage(Message message) {
            callerThread.set(Thread.currentThread());
            calls.incrementAndGet();
            invoked.countDown();
        }
    }

    @Test
    public void delegateRunsOnExecutorNotCaller() throws Exception {
        RecordingProcessor delegate = new RecordingProcessor(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        wrapper = new AsyncMessageProcessor(delegate, pool);

        Thread callerThread = Thread.currentThread();
        wrapper.processMessage(new Message((Connection) null));

        assertTrue("delegate was never invoked", delegate.invoked.await(2, TimeUnit.SECONDS));
        assertNotNull(delegate.callerThread.get());
        assertNotEquals("delegate must run on executor, not caller",
                callerThread, delegate.callerThread.get());
        assertEquals(1, delegate.calls.get());
    }

    @Test
    public void shutdownWaitsForInFlightTasks() throws Exception {
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        MessageProcessor delegate = new MessageProcessor() {
            @Override
            public void processMessage(Message message) {
                entered.countDown();
                try {
                    hold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        wrapper = new AsyncMessageProcessor(delegate, pool);

        wrapper.processMessage(new Message((Connection) null));
        assertTrue("task did not start", entered.await(2, TimeUnit.SECONDS));

        // Release the in-flight task on a separate thread so shutdown can drain it.
        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            hold.countDown();
        }).start();

        long start = System.nanoTime();
        wrapper.shutdown(3, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue("shutdown returned too fast — did it await termination?", elapsedMs >= 40);
        assertTrue("executor not terminated after shutdown", pool.isTerminated());
        wrapper = null; // already shut down
    }

    @Test
    public void submitAfterShutdownIsLogged_notThrown() {
        RecordingProcessor delegate = new RecordingProcessor(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        wrapper = new AsyncMessageProcessor(delegate, pool);
        wrapper.shutdown(1, TimeUnit.SECONDS);

        // Must not throw — RejectedExecutionException is logged, not propagated, so a
        // slow shutdown can never crash the dispatcher thread.
        wrapper.processMessage(new Message((Connection) null));
        wrapper = null;
    }

    @Test(expected = NullPointerException.class)
    public void nullDelegateRejected() {
        new AsyncMessageProcessor(null, Executors.newSingleThreadExecutor());
    }

    @Test(expected = NullPointerException.class)
    public void nullExecutorRejected() {
        new AsyncMessageProcessor(new RecordingProcessor(0), null);
    }

    @Test
    public void fixedHandlerPoolFactory_alwaysAvailable() throws Exception {
        ExecutorService pool = AsyncMessageProcessor.newFixedHandlerPool(2);
        try {
            assertNotNull(pool);
            CountDownLatch done = new CountDownLatch(1);
            pool.submit(done::countDown);
            assertTrue(done.await(1, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedHandlerPool_rejectsZero() {
        AsyncMessageProcessor.newFixedHandlerPool(0);
    }

    @Test
    public void virtualThreadExecutorFactory_returnsWorkingExecutor() throws Exception {
        ExecutorService pool = AsyncMessageProcessor.newVirtualThreadExecutor();
        try {
            assertNotNull(pool);
            CountDownLatch done = new CountDownLatch(1);
            pool.submit(done::countDown);
            assertTrue(done.await(1, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void delegateIOExceptionIsCaught_notPropagated() throws Exception {
        final CountDownLatch invoked = new CountDownLatch(1);
        MessageProcessor delegate = new MessageProcessor() {
            @Override
            public void processMessage(Message message) throws IOException {
                invoked.countDown();
                throw new IOException("simulated");
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        wrapper = new AsyncMessageProcessor(delegate, pool);

        // Must not throw — wrapper catches and logs IOException so the dispatcher
        // pipeline keeps going.
        wrapper.processMessage(new Message((Connection) null));
        assertTrue(invoked.await(2, TimeUnit.SECONDS));
    }

    @Test
    public void handlerExceptionClosesConnectionAndReleasesToken() {
        Connection conn = mock(Connection.class);
        MessageProcessor delegate = message -> { throw new RuntimeException("boom"); };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        wrapper = new AsyncMessageProcessor(delegate, pool);

        wrapper.processMessage(new Message(conn));
        wrapper.shutdown(2, TimeUnit.SECONDS);   // awaits the task, so the catch/finally have run
        wrapper = null;

        verify(conn).enterInFlight();
        verify(conn).leaveInFlight();   // token released despite the throw
        verify(conn).close();           // fail-fast: peer isn't left hanging
    }

    @Test
    public void submitRejectedReleasesToken() {
        Connection conn = mock(Connection.class);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        wrapper = new AsyncMessageProcessor(new RecordingProcessor(0), pool);
        wrapper.shutdown(1, TimeUnit.SECONDS);   // reject subsequent submits

        wrapper.processMessage(new Message(conn));   // submit throws RejectedExecutionException
        wrapper = null;

        verify(conn).enterInFlight();
        verify(conn).leaveInFlight();   // token released even though the task never ran
    }
}
