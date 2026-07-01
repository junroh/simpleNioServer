package com.jun.app;

import com.jun.http.HttpHeaders;
import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;

import java.nio.charset.StandardCharsets;

/** Demo handler: echoes the request body, or the URI if the body is empty. */
public class EchoHandler implements HttpRequestHandler {
    @Override
    public HttpResponse handle(HttpRequest request) {
        byte[] body = (request.getBody().length > 0)
                ? request.getBody()
                : request.getRequestUri().getBytes(StandardCharsets.UTF_8);
        return new HttpResponse()
                .status(HttpResponse.STATUS_OK, HttpResponse.REASON_OK)
                .header(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_TYPE_TEXT_PLAIN)
                .body(body);
    }
}
