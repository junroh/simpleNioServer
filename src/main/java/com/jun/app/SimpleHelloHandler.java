package com.jun.app;

import com.jun.http.HttpHeaders;
import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;

import java.util.concurrent.atomic.AtomicInteger;

/** Demo handler: replies with "Hello World" formatted via the body template. */
public class SimpleHelloHandler implements HttpRequestHandler {

    private final String bodyFormat;
    private final int transportId;
    private final AtomicInteger requestCounter = new AtomicInteger();

    public SimpleHelloHandler(String bodyFormat, int transportId) {
        this.bodyFormat = bodyFormat;
        this.transportId = transportId;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        int id = requestCounter.incrementAndGet();
        return new HttpResponse()
                .status(HttpResponse.STATUS_OK, HttpResponse.REASON_OK)
                .header(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_TYPE_TEXT_HTML)
                .body(String.format(bodyFormat, transportId, id));
    }
}
