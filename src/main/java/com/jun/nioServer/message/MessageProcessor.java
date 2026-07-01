package com.jun.nioServer.message;

import java.io.IOException;

/**
 * NIO-side application contract. Receives a parsed {@link Message}; the {@link Message}'s
 * own {@link com.jun.nioServer.Connection} is the response target.
 */
public interface MessageProcessor {
    void processMessage(Message message) throws IOException;
}
