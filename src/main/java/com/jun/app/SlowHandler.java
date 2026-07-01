package com.jun.app;

import com.jun.http.HttpHeaders;
import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;

/**
 * Benchmark handler: sleeps for {@code sleepMs} before responding. Mimics a blocking
 * downstream call (DB, RPC) without burning CPU. Used in experiments E3 and E7 to expose
 * thread starvation under {@link com.jun.threadedServer.ThreadedServer} and dispatcher
 * saturation under {@link com.jun.nioServer.NioServerService}.
 */
public class SlowHandler implements HttpRequestHandler {

    private final long sleepMs;

    public SlowHandler(long sleepMs) {
        if (sleepMs < 0) {
            throw new IllegalArgumentException("sleepMs must be >= 0");
        }
        this.sleepMs = sleepMs;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new HttpResponse()
                        .status(HttpResponse.STATUS_SERVICE_UNAVAILABLE, HttpResponse.REASON_SERVICE_UNAVAILABLE)
                        .body("interrupted");
            }
        }
        return new HttpResponse()
                .status(HttpResponse.STATUS_OK, HttpResponse.REASON_OK)
                .header(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_TYPE_TEXT_PLAIN)
                .body("slept " + sleepMs + "ms");
    }
}
