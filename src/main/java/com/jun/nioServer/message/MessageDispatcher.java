package com.jun.nioServer.message;

import com.jun.nioServer.Connection;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Drains a per-reactor queue of ready {@link Connection}s, runs each connection's own
 * {@link MessageReader} (created eagerly when the connection was registered), and
 * dispatches completed messages to {@link MessageProcessor}.
 *
 * <p>Pure {@link Runnable}: {@link com.jun.nioServer.NioServerService} owns the thread
 * that calls {@link #run()}. One dispatcher per {@link com.jun.nioServer.IOReactor}.
 */
public class MessageDispatcher implements Runnable {

    private static final Logger log = Logger.getLogger(MessageDispatcher.class);

    private final BlockingQueue<Connection> readyQueue = new LinkedBlockingQueue<>();
    private final MessageProcessor messageProcessor;

    public MessageDispatcher(MessageProcessor messageProcessor) {
        this.messageProcessor = Objects.requireNonNull(messageProcessor, "messageProcessor");
    }

    public boolean enqueue(Connection connection) {
        // Take an in-flight token spanning enqueue→process so a graceful close cannot fire in
        // the gap before this pass runs. Released in processSocketInternal's finally.
        connection.enterInFlight();
        if (readyQueue.offer(connection)) {
            return true;
        }
        connection.leaveInFlight();
        return false;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            Connection connection = null;
            try {
                connection = readyQueue.take();
                processSocketInternal(connection);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Exception in MessageDispatcher run loop", e);
                if (connection != null) {
                    connection.close();
                }
            }
        }
    }

    void processSocketInternal(Connection connection) {
        try {
            List<ByteBuffer> socketReadData = connection.drainSocketReadData();
            if (socketReadData != null && !socketReadData.isEmpty()) {
                connection.getMessageReader().parse(connection, socketReadData);
            }
            dispatchCompletedMessages(connection);
        } catch (Exception e) {
            // A handler (or parser) blew up. Don't leave the peer hanging for its full
            // request-timeout — drop the socket so the client sees connection-close and
            // can fail fast. The acceptor remains healthy for new connections.
            log.error("Error processing socket: " + connection.getSocketId(), e);
            connection.close();
        } finally {
            // Release this pass's in-flight token; if it was the last unit of work and a close
            // was requested with nothing left to write, this closes the connection.
            connection.leaveInFlight();
        }
    }

    private void dispatchCompletedMessages(Connection connection) {
        List<Message> messages = connection.drainReadyMessages();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            try {
                messageProcessor.processMessage(message);
            } catch (IOException e) {
                log.error("Failed to dispatch message for socket " + connection.getSocketId(), e);
            }
        }
    }
}
