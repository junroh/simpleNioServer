package com.jun.http;

import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.Message;
import java.io.IOException;

public interface NioMessageHandler {
    void processMessage(Message requestMessage, ConnectedSocket connectedSocket) throws IOException;
}
