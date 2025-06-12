package com.jun.nioServer.handler;

// import com.jun.config.ServerConfig; // Unused
import com.jun.http.NioMessageHandler;
import com.jun.nioServer.ConnectedSocket;
import com.jun.nioServer.msg.IMessageReader;
import com.jun.nioServer.msg.Message;
import com.jun.nioServer.msg.IMessageReaderFactory; // Added for constructor
import com.jun.nioServer.msg.http.HttpMessageReaderFactory;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
// import java.nio.channels.SelectionKey; // Unused
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MsgHandler implements Runnable {

    private static final Logger log = Logger.getLogger(MsgHandler.class);
    private static final BlockingQueue<ConnectedSocket> readyToMsgQ = new LinkedBlockingQueue<>();
    private IMessageReader msgParser; // Will be initialized via factory in constructor
    private final Thread thread;
    private final IMessageReaderFactory messageReaderFactory;
    private final NioMessageHandler messageProcessor;

    public boolean enqueue(ConnectedSocket socket) {
        return readyToMsgQ.offer(socket);
    }

    public MsgHandler(IMessageReaderFactory readerFactory, NioMessageHandler messageProcessor) {
        this.messageReaderFactory = readerFactory;
        this.msgParser = this.messageReaderFactory.createMessageReader();
        this.messageProcessor = messageProcessor;
        this.thread = new Thread(this, MsgHandler.class.getSimpleName());
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
            ConnectedSocket socket = null; // Initialize to null
            try {
                socket = readyToMsgQ.take();
                if (socket != null) { // Ensure socket is not null before processing
                    processSocketInternal(socket);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("MsgHandler interrupted, stopping.");
            } catch (Exception e) {
                log.error("Exception in MsgHandler run loop", e); // General error
                if (socket != null) {
                    log.error("Exception occurred while processing socket: " + socket.getSocketId() + ". Closing socket.", e);
                    socket.close(); // Close the specific socket that caused trouble
                }
            }
        }
        log.info("Message handler stopped");
    }

    // New method
    void processSocketInternal(ConnectedSocket socket) { // Removed throws IOException as internal methods handle them
        try {
            List<ByteBuffer> socketReadData = socket.getSocketReadData();
            if (socketReadData == null) { // Defensive check
                 log.warn("socketReadData is null for socket: " + socket.getSocketId());
                 return;
            }

            if (!socketReadData.isEmpty()) {
                int lastCompIdx = msgParser.parse(socket, socketReadData);
                if (lastCompIdx >= 0) {
                    // Ensure subList parameters are valid
                    if (lastCompIdx < socketReadData.size()) {
                        socketReadData.subList(0, lastCompIdx + 1).clear();
                    } else {
                        log.warn("lastCompIdx is out of bounds for socketReadData for socket: " + socket.getSocketId());
                        socketReadData.clear(); // Clear all if index is wrong
                    }
                }
            }
            processCompleteMsg(socket); // processCompleteMsg itself has a try-catch
        } catch (Exception e) {
            // This catch block is for exceptions specifically from processing this socket internally
            log.error("Error during internal processing of socket: " + socket.getSocketId(), e);
            // The run() method's main catch will also log and then close this socket.
            // Re-throw if we want the main run() method's catch to handle socket closure.
            // For now, just log here. The main loop's catch will handle closure.
            // Or, more directly, if this method is expected to fully handle its errors including socket closure:
            // socket.close(); // but this might be redundant if run() also does it.
            // Let's rely on the run() method's catch block for socket closure.
        }
    }

    private void processCompleteMsg(ConnectedSocket socket) {
        List<Message> fullMessages = socket.getReadReadyMessages();
        if (fullMessages != null && !fullMessages.isEmpty()) {
            log.debug("Ready message num is " + fullMessages.size());
            for (Message message : fullMessages) {
                try {
                    process(message);
                } catch (IOException e) {
                    log.error("Failed to add proc Q for socket " + socket.getSocketId(), e);
                }
            }
            fullMessages.clear();
        }
    }

    private void process(Message message) throws IOException {
        // The ConnectedSocket is available via message.socketChannel
        this.messageProcessor.processMessage(message, message.socketChannel);
    }
}
