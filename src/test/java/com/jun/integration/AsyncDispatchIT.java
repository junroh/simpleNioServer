package com.jun.integration;

import com.jun.app.SimpleHelloHandler;
import com.jun.app.SlowHandler;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRouter;
import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jun.integration.support.TestClients.HTTP_OK;
import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.get;
import static com.jun.integration.support.TestClients.httpUrl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The point of {@code AsyncMessageProcessor}: a blocking handler must not stall the
 * dispatcher. Verifies that observable parallelism, not just config — N concurrent
 * requests with a 200 ms sleeping handler must complete in roughly one sleep, not N.
 *
 * <p>The contrast between sync NIO and async NIO is also covered — sync should be
 * roughly serial (one dispatcher thread per reactor), async should be parallel up to
 * the handler-pool size.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AsyncDispatchIT {

    private static final String PATH_SLEEP    = "/sleep";
    private static final int    SLEEP_MS      = 200;
    private static final int    PARALLEL_REQS = 5;

    private final TestServers servers = new TestServers();

    @After
    public void tearDown() {
        servers.stopAll();
    }

    @Test
    public void asyncDispatch_parallelizesBlockingHandlers() throws Exception {
        int port = startNio(true);

        long elapsed = runParallelSleepRequests(port);

        // Lower bound: each request waits at least SLEEP_MS.
        // Upper bound: with async dispatch onto a pool of HANDLER_POOL workers, all
        // PARALLEL_REQS requests should run in parallel, so ~SLEEP_MS + overhead.
        // We allow a generous ceiling (3× SLEEP_MS) for GC / scheduling jitter.
        long ceiling = SLEEP_MS * 3L;
        assertTrue("async dispatch should overlap requests; total elapsed " + elapsed
                + " ms exceeds ceiling " + ceiling + " ms", elapsed < ceiling);
    }

    @Test
    public void syncDispatch_serializesBlockingHandlers() throws Exception {
        int port = startNio(false);

        long elapsed = runParallelSleepRequests(port);

        // Sync dispatch: one dispatcher thread per reactor (we configured 1 reactor),
        // so requests should run roughly serially. Floor at half the serial expectation
        // to leave headroom for jitter while still detecting "actually serial".
        long floor = SLEEP_MS * (long) (PARALLEL_REQS / 2);
        assertTrue("sync dispatch should serialize; total elapsed " + elapsed
                + " ms is below floor " + floor + " ms (looks parallel — wrong transport?)",
                elapsed > floor);
    }

    /** Runs PARALLEL_REQS sleeping requests in parallel and returns total wall time. */
    private long runParallelSleepRequests(int port) throws InterruptedException {
        ExecutorService exec = Executors.newFixedThreadPool(PARALLEL_REQS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PARALLEL_REQS);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int i = 0; i < PARALLEL_REQS; i++) {
                exec.submit(() -> {
                    try {
                        start.await();
                        HttpResponse<String> r = get(httpUrl(port, PATH_SLEEP));
                        if (r.statusCode() == HTTP_OK) {
                            ok.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }
            long t0 = System.nanoTime();
            start.countDown();
            assertTrue("requests timed out",
                    done.await(SLEEP_MS * PARALLEL_REQS * 3L, TimeUnit.MILLISECONDS));
            long elapsed = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals("expected all " + PARALLEL_REQS + " requests to succeed",
                    PARALLEL_REQS, ok.get());
            return elapsed;
        } finally {
            exec.shutdownNow();
        }
    }

    private int startNio(boolean asyncDispatch) {
        HttpRequestHandler router = new HttpRouter()
                .get(PATH_HELLO, new SimpleHelloHandler(TestServers.HELLO_BODY_FORMAT, 0))
                .get(PATH_SLEEP, new SlowHandler(SLEEP_MS));
        return servers.startNio(router, asyncDispatch);
    }
}
