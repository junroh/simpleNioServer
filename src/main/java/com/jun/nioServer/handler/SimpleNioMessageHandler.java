package com.jun.nioServer.handler;

import com.jun.config.ServerConfig;
import com.jun.http.NioMessageHandler;
import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.Message;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.channels.SelectionKey;

public class SimpleNioMessageHandler implements NioMessageHandler {
    private static final Logger log = Logger.getLogger(SimpleNioMessageHandler.class);

    @Override
    public void processMessage(Message requestMessage, ConnectedSocket connectedSocket) throws IOException {
        String responseBody = String.format(
            ServerConfig.NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT,
            connectedSocket.getSocketId(),
            requestMessage.getId()
        );

        byte[] responseBodyBytes;
        try {
            responseBodyBytes = responseBody.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Should not happen with UTF-8
            log.error("UTF-8 not supported, falling back to default charset for response body", e);
            responseBodyBytes = responseBody.getBytes();
        }
        int contentLength = responseBodyBytes.length;

        String httpResponseHeader = ServerConfig.NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 +
                                   contentLength +
                                   ServerConfig.NIO_MSG_HANDLER_STATIC_RESPONSE_PART2;

        byte[] httpResponseHeaderBytes;
        try {
            httpResponseHeaderBytes = httpResponseHeader.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("UTF-8 not supported, falling back to default charset for response header", e);
            httpResponseHeaderBytes = httpResponseHeader.getBytes();
        }

        log.debug("Processing socket: " + connectedSocket.getSocketId() + " - " + requestMessage.getId());
        Message response = new Message(connectedSocket, requestMessage.getId());

        response.writeToMessage(httpResponseHeaderBytes);
        response.writeToMessage(responseBodyBytes);

        connectedSocket.addWriteReadyMsg(response);

        if(connectedSocket.prepareBuffersForWriting()) {
            log.debug("Response message is ready on socket " + connectedSocket.getSocketId());
            if (connectedSocket.getKey() != null && connectedSocket.getKey().isValid()) {
                connectedSocket.addInterestedOps(SelectionKey.OP_WRITE);
                if (connectedSocket.getKey().selector() != null && connectedSocket.getKey().selector().isOpen()) {
                    connectedSocket.getKey().selector().wakeup();
                } else {
                    log.warn("Selector is not open for socket: " + connectedSocket.getSocketId() + ", cannot wakeup.");
                }
            } else {
                log.warn("SelectionKey is invalid or null for socket: " + connectedSocket.getSocketId() + ", cannot set OP_WRITE or wakeup selector.");
            }
        } else {
            log.debug("Response message buffer could not be made ready for socket: " + connectedSocket.getSocketId());
        }
    }
}
