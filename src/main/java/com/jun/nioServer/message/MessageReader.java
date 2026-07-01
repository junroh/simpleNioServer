package com.jun.nioServer.message;

import com.jun.nioServer.Connection;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Stateful, per-connection message parser. {@link #parse} consumes all bytes from the
 * supplied buffers, buffering partial messages internally across calls. Each completed
 * message is delivered via {@link Connection#addReadReadyMsg(Message)}.
 */
public interface MessageReader {
    void parse(Connection connection, List<ByteBuffer> buffers);
}
