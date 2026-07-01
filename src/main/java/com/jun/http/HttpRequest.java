package com.jun.http;

/** Parsed HTTP request, server-agnostic. */
public final class HttpRequest {

    private final String method;
    private final String requestUri;
    private final String httpVersion;
    private final HttpHeaders headers;
    private final byte[] body;

    public HttpRequest(String method, String requestUri, String httpVersion,
                       HttpHeaders headers, byte[] body) {
        this.method = method;
        this.requestUri = requestUri;
        this.httpVersion = httpVersion;
        this.headers = headers;
        this.body = body;
    }

    public String getMethod() {
        return method;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public boolean isKeepAlive() {
        return headers.keepAlive;
    }

    /** A HEAD response must carry the same headers as GET but no body (RFC 7231 §4.3.2). */
    public boolean isHead() {
        return "HEAD".equalsIgnoreCase(method);
    }
}
