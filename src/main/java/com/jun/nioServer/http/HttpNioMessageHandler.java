package com.jun.nioServer.http;

import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpResponse;
import com.jun.nioServer.Connection;
import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageProcessor;
import org.apache.log4j.Logger;

/**
 * Bridges the NIO {@link MessageProcessor} contract to the unified
 * {@link HttpRequestHandler}. Pulls the {@link HttpRequest} from the message payload
 * (set by {@link HttpNioMessageReader}), runs the application handler, and queues the
 * response bytes onto the connection. The same {@code HttpRequestHandler} drives the
 * threaded server.
 */
public class HttpNioMessageHandler implements MessageProcessor {

    private static final Logger log = Logger.getLogger(HttpNioMessageHandler.class);

    private final HttpRequestHandler appHandler;

    public HttpNioMessageHandler(HttpRequestHandler appHandler) {
        this.appHandler = appHandler;
    }

    @Override
    public void processMessage(Message requestMessage) {
        Connection connection = requestMessage.getConnection();
        HttpRequest req;
        try {
            req = requestMessage.getPayloadAs(HttpRequest.class);
        } catch (IllegalStateException e) {
            log.error("Unexpected payload on socket " + connection.getSocketId() + ": " + e.getMessage());
            connection.close();
            return;
        }
        HttpResponse resp = appHandler.handle(req);

        Message response = new Message(connection, requestMessage.getId());
        response.writeToMessage(resp.toBytes(req.isHead()));
        connection.sendResponse(response);

        // Match the threaded transport: honour the peer's `Connection: close`. The actual
        // close happens after SocketWriter drains every buffered byte.
        if (!req.isKeepAlive()) {
            connection.closeAfterPendingWrites();
        }
    }
}
