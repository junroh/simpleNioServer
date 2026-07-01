package com.jun.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP headers. Lookup is case-insensitive (RFC 7230 §3.2). Well-known headers
 * (Content-Length, Transfer-Encoding, Connection) are decoded into typed fields
 * by {@link HttpRequestParser}; raw values remain available via {@link #get} / {@link #all}.
 */
public class HttpHeaders {

    public static final String CONTENT_LENGTH    = "Content-Length";
    public static final String CONTENT_TYPE      = "Content-Type";
    public static final String CONNECTION        = "Connection";
    public static final String TRANSFER_ENCODING = "Transfer-Encoding";

    public static final String VALUE_KEEP_ALIVE = "keep-alive";
    public static final String VALUE_CLOSE      = "close";
    public static final String VALUE_CHUNKED    = "chunked";

    public static final String CONTENT_TYPE_TEXT_PLAIN = "text/plain";
    public static final String CONTENT_TYPE_TEXT_HTML  = "text/html";

    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> orderedHeaders = new LinkedHashMap<>();

    /** -1 if absent. */
    public long contentLength = -1;
    public boolean chunkedTransferEncoding;
    /** HTTP/1.1 default; HTTP/1.0 flips to false; explicit {@code Connection:} header overrides. */
    public boolean keepAlive = true;

    public void put(String name, String value) {
        headers.put(name, value);
        orderedHeaders.put(name, value);
    }

    public String get(String name) {
        return headers.get(name);
    }

    public boolean contains(String name) {
        return headers.containsKey(name);
    }

    public Map<String, String> all() {
        return orderedHeaders;
    }
}
