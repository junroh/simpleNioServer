package com.jun.http;

/**
 * Application contract: take a parsed {@link HttpRequest}, return an {@link HttpResponse}.
 * Used by both server transports.
 *
 * <p>Implementations run on the dispatcher thread (NIO) or the per-request worker thread
 * (threaded), so they should be fast — slow handlers block one of those threads.
 */
public interface HttpRequestHandler {
    HttpResponse handle(HttpRequest request);
}
