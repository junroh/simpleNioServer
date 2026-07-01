package com.jun.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes requests to per-route handlers. Implements {@link HttpRequestHandler} so it can
 * be plugged into either server transport.
 *
 * <p>Match order: exact (method, path) → prefix (first wins) → not-found fallback.
 * Query strings are stripped before matching.
 *
 * <p>{@link #route} / {@link #prefix} are not thread-safe; configure all routes during
 * wiring, then expose the router to traffic.
 */
public final class HttpRouter implements HttpRequestHandler {

    public static final String METHOD_GET    = "GET";
    public static final String METHOD_POST   = "POST";
    public static final String METHOD_PUT    = "PUT";
    public static final String METHOD_DELETE = "DELETE";
    public static final String METHOD_HEAD   = "HEAD";

    private static final String NOT_FOUND_BODY = "Not Found";

    public static final HttpRequestHandler DEFAULT_NOT_FOUND = req -> new HttpResponse()
            .status(HttpResponse.STATUS_NOT_FOUND, HttpResponse.REASON_NOT_FOUND)
            .header(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_TYPE_TEXT_PLAIN)
            .body(NOT_FOUND_BODY);

    private final Map<String, HttpRequestHandler> exactRoutes = new HashMap<>();
    private final List<PrefixRoute> prefixRoutes = new ArrayList<>();
    private HttpRequestHandler notFound = DEFAULT_NOT_FOUND;

    public HttpRouter route(String method, String path, HttpRequestHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        exactRoutes.put(key(method, path), handler);
        return this;
    }

    public HttpRouter get(String path, HttpRequestHandler handler) {
        return route(METHOD_GET, path, handler);
    }

    public HttpRouter post(String path, HttpRequestHandler handler) {
        return route(METHOD_POST, path, handler);
    }

    public HttpRouter put(String path, HttpRequestHandler handler) {
        return route(METHOD_PUT, path, handler);
    }

    public HttpRouter delete(String path, HttpRequestHandler handler) {
        return route(METHOD_DELETE, path, handler);
    }

    public HttpRouter head(String path, HttpRequestHandler handler) {
        return route(METHOD_HEAD, path, handler);
    }

    public HttpRouter prefix(String pathPrefix, HttpRequestHandler handler) {
        Objects.requireNonNull(pathPrefix, "pathPrefix");
        Objects.requireNonNull(handler, "handler");
        prefixRoutes.add(new PrefixRoute(pathPrefix, handler));
        return this;
    }

    /** {@code null} resets to the default 404 handler. */
    public HttpRouter notFound(HttpRequestHandler handler) {
        this.notFound = (handler != null) ? handler : DEFAULT_NOT_FOUND;
        return this;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        String path = stripQuery(request.getRequestUri());

        HttpRequestHandler exact = exactRoutes.get(key(request.getMethod(), path));
        if (exact != null) {
            return exact.handle(request);
        }

        for (PrefixRoute pr : prefixRoutes) {
            if (path.startsWith(pr.prefix)) {
                return pr.handler.handle(request);
            }
        }
        return notFound.handle(request);
    }

    private static String key(String method, String path) {
        return method + " " + path;
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        if (q < 0) {
            return uri;
        }
        return uri.substring(0, q);
    }

    private static final class PrefixRoute {
        final String prefix;
        final HttpRequestHandler handler;

        PrefixRoute(String prefix, HttpRequestHandler handler) {
            this.prefix = prefix;
            this.handler = handler;
        }
    }
}
