package com.jun.nioServer.msg;

import com.jun.nioServer.ConnectedSocket;

import java.nio.ByteBuffer;
import java.util.List;

public interface IMessageReader {
    int parse(ConnectedSocket socket, List<ByteBuffer> buffers);
}
