package com.jun.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Server-agnostic HTTP response builder. {@link #toBytes()} produces the wire bytes. */
public final class HttpResponse {

    public static final int STATUS_OK                  = 200;
    public static final int STATUS_NOT_FOUND           = 404;
    public static final int STATUS_SERVICE_UNAVAILABLE = 503;

    public static final String REASON_OK                  = "OK";
    public static final String REASON_NOT_FOUND           = "Not Found";
    public static final String REASON_SERVICE_UNAVAILABLE = "Service Unavailable";

    public static final String DEFAULT_HTTP_VERSION = "HTTP/1.1";
    private static final String CRLF                 = "\r\n";

    private int status = STATUS_OK;
    private String reason = REASON_OK;
    private String httpVersion = DEFAULT_HTTP_VERSION;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    public HttpResponse status(int status, String reason) {
        this.status = status;
        this.reason = reason;
        return this;
    }

    public HttpResponse httpVersion(String version) {
        this.httpVersion = version;
        return this;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.body = Objects.requireNonNullElseGet(body, () -> new byte[0]);
        if (!headers.containsKey(HttpHeaders.CONTENT_LENGTH)) {
            headers.put(HttpHeaders.CONTENT_LENGTH, Integer.toString(this.body.length));
        }
        return this;
    }

    public HttpResponse body(String body) {
        if (body == null) {
            return body((byte[]) null);
        }
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toBytes() {
        return toBytes(false);
    }

    /** @param omitBody true for HEAD responses: emit status line + headers (incl. the
     *                  Content-Length the body would have) but no body bytes. */
    public byte[] toBytes(boolean omitBody) {
        // Build the whole head once, encode once — instead of a ByteArrayOutputStream plus a
        // getBytes() per line — then splice the body with a single copy.
        StringBuilder head = new StringBuilder(64 + headers.size() * 32);
        head.append(httpVersion).append(' ').append(status).append(' ').append(reason).append(CRLF);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append(CRLF);
        }
        head.append(CRLF);
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);

        if (omitBody || body.length == 0) {
            return headBytes;
        }
        byte[] out = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(body, 0, out, headBytes.length, body.length);
        return out;
    }
}
