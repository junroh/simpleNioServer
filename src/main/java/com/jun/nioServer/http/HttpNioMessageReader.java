package com.jun.nioServer.http;

import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestParser;
import com.jun.nioServer.Connection;
import com.jun.nioServer.message.Message;
import com.jun.nioServer.message.MessageReader;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * NIO adapter for the unified HTTP library. Wraps a {@link HttpRequestParser} and forwards
 * each parsed {@link HttpRequest} as a {@link Message} via
 * {@link Connection#addReadReadyMsg(Message)}; the request is attached to the message
 * header so {@link HttpNioMessageHandler} can read it back.
 */
public class HttpNioMessageReader implements MessageReader {

    private static final Logger log = Logger.getLogger(HttpNioMessageReader.class);

    private final HttpRequestParser parser;
    private Connection currentConnection;

    public HttpNioMessageReader() {
        this.parser = new HttpRequestParser(new HttpRequestParser.Listener() {
            @Override
            public void onRequest(HttpRequest req) {
                Message m = new Message(currentConnection);
                m.setPayload(req);
                currentConnection.addReadReadyMsg(m);
            }

            @Override
            public void onError(String message) {
                if (currentConnection != null) {
                    log.warn("HTTP parse error on socket " + currentConnection.getSocketId()
                            + ": " + message);
                    currentConnection.close();
                } else {
                    log.warn("HTTP parse error: " + message);
                }
            }
        });
    }

    @Override
    public void parse(Connection connection, List<ByteBuffer> buffers) {
        this.currentConnection = connection;
        try {
            for (ByteBuffer b : buffers) {
                int len = b.remaining();
                if (len == 0) {
                    continue;
                }
                if (b.hasArray()) {
                    parser.feed(b.array(), b.position() + b.arrayOffset(), len);
                    b.position(b.position() + len);
                } else {
                    byte[] copy = new byte[len];
                    b.get(copy);
                    parser.feed(copy, 0, len);
                }
            }
        } finally {
            this.currentConnection = null;
        }
    }
}
