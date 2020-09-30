package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.IMessageReader;
import com.jun.nioServer.msg.Message;
import com.jun.nioServer.msg.http.HttpMessageReaderFactory;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MsgHandler implements Runnable {

    private static final Logger log = Logger.getLogger(MsgHandler.class);
    private static final BlockingQueue<ConnectedSocket> readyToMsgQ = new LinkedBlockingQueue<>();
    private final IMessageReader msgParser;
    private final Thread thread;

    public boolean enqueue(ConnectedSocket socket) {
        return readyToMsgQ.offer(socket);
    }

    public MsgHandler() {
        msgParser = new HttpMessageReaderFactory().createMessageReader();
        thread = new Thread(this, MsgHandler.class.getSimpleName());
    }

    public synchronized void start() {
        thread.start();
    }

    public synchronized void stop() {
        thread.interrupt();
        try {
            thread.join();
        } catch (Exception e) {
            log.error("Failed to stop " + this.getClass().getSimpleName());
        }
    }

    @Override
    public void run() {
        log.info("Message handler started");
        while(!Thread.currentThread().isInterrupted()) {
            try {
                ConnectedSocket socket = readyToMsgQ.take();
                List<ByteBuffer> socketReadData = socket.getSocketReadData();
                // todo: how to handle remaining message (partial msg)
                // todo: msg parse -> reader
                if(!socketReadData.isEmpty()) {
                    int lastCompIdx = msgParser.parse(socket, socketReadData);
                    if (lastCompIdx >= 0) {
                        socketReadData.subList(0, lastCompIdx + 1).clear();
                    }
                    processCompleteMsg(socket);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error(e, e);
                // todo: exception handling
            }
        }
        log.info("Message handler stopped");
    }

    private void processCompleteMsg(ConnectedSocket socket) {
        List<Message> fullMessages = socket.getReadReadyMessages();
        if (fullMessages != null && !fullMessages.isEmpty()) {
            log.debug("Ready message num is " + fullMessages.size());
            for (Message message : fullMessages) {
                try {
                    process(message);
                } catch (IOException e) {
                    log.error("Failed to add proc Q");
                }
            }
            fullMessages.clear();
        }
    }

    private void process(Message message) throws IOException {
        ConnectedSocket connectedSocket = message.socketChannel;
        String httpResponse = String.format(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 38\r\n" +
                "Content-Type: text/html\r\n" +
                "\r\n" +
                "<html><body>Hello World(%d-%d)</body></html>",
            connectedSocket.getSocketId(), message.getId());
        byte[] httpResponseBytes = httpResponse.getBytes("UTF-8");

        log.debug("Processing socket: " + connectedSocket.getSocketId() + " - " + message.getId());
        Message response = new Message(connectedSocket, message.getId());
        response.writeToMessage(httpResponseBytes);
        connectedSocket.addWriteReadyMsg(response);

        if(connectedSocket.makeReadyBuffer()) {
            log.debug("message is ready on socket " + connectedSocket.getSocketId());
            connectedSocket.addInterestedOps(SelectionKey.OP_WRITE);
            connectedSocket.getKey().selector().wakeup();
        }
    }
}
