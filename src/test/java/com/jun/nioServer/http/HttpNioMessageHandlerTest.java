package com.jun.nioServer.http;

import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;
import com.jun.nioServer.Connection;
import com.jun.nioServer.message.Message;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the NIO bridge: a {@link Message} carrying an {@link HttpRequest} as its header
 * is dispatched to the unified {@link HttpRequestHandler}, then the encoded response is
 * handed to the connection via {@code sendResponse}.
 */
public class HttpNioMessageHandlerTest {

    private Connection connection;
    private HttpRequestHandler appHandler;
    private HttpNioMessageHandler bridge;

    @Before
    public void setUp() {
        connection = mock(Connection.class);
        appHandler = mock(HttpRequestHandler.class);
        bridge = new HttpNioMessageHandler(appHandler);
    }

    @Test
    public void dispatchesHttpRequestToAppAndQueuesResponse() {
        HttpRequest req = new HttpRequest("GET", "/hello", "HTTP/1.1",
                new com.jun.http.HttpHeaders(), new byte[0]);
        Message msg = new Message(connection);
        msg.setPayload(req);

        HttpResponse resp = new HttpResponse().status(200, "OK").body("hi");
        when(appHandler.handle(req)).thenReturn(resp);

        bridge.processMessage(msg);

        verify(appHandler, times(1)).handle(req);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(connection).sendResponse(captor.capture());
        Message sent = captor.getValue();
        assertEquals(1, sent.getChunks().size());
        assertSame(connection, sent.getConnection());
    }

    @Test
    public void closesConnectionWhenHeaderPayloadIsNotHttpRequest() {
        Message bogus = new Message(connection);
        bogus.setPayload("not an HttpRequest");

        bridge.processMessage(bogus);

        verify(connection).close();
        verify(appHandler, org.mockito.Mockito.never()).handle(any(HttpRequest.class));
    }
}
