package com.jun.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String statusLine = httpVersion + " " + status + " " + reason + CRLF;
            out.write(statusLine.getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String header = e.getKey() + ": " + e.getValue() + CRLF;
                out.write(header.getBytes(StandardCharsets.UTF_8));
            }
            out.write(CRLF.getBytes(StandardCharsets.UTF_8));
            if (!omitBody) {
                out.write(body);
            }
            return out.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws; satisfy the checked exception.
            throw new RuntimeException(e);
        }
    }
}
