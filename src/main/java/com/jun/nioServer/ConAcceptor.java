package com.jun.nioServer;

import org.apache.log4j.Logger;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.channels.SocketChannel;
// No longer need AtomicInteger import

class ConAcceptor implements Runnable {
    private static final Logger log = Logger.getLogger(ConAcceptor.class);

    private final SocketChannel socketChannel;
    private final IOReactor targetReactor;
    private final SSLContext sslContext; // Can be null if SSL is not used
    private final int socketId;

    ConAcceptor(SocketChannel socketChannel, IOReactor targetReactor, SSLContext sslContext, int socketId) {
        this.socketChannel = socketChannel;
        this.targetReactor = targetReactor;
        this.sslContext = sslContext;
        this.socketId = socketId;
    }

    @Override
    public void run() {
        if (socketChannel == null) {
            log.warn("ConAcceptor run with null socketChannel.");
            return;
        }
        if (targetReactor == null) {
            log.error("ConAcceptor run with null targetReactor for socketId: " + this.socketId + ". Closing socket.");
            try {
                socketChannel.close();
            } catch (IOException e) {
                log.error("Failed to close socketChannel in ConAcceptor (targetReactor was null) for socketId: " + this.socketId, e);
            }
            return;
        }
        try {
            regSocket(socketChannel);
        } catch (IOException e) { // This IOException is from regSocket's signature, typically if sc.close() inside it fails.
            log.error("IOException in ConAcceptor's regSocket method for socketId: " + this.socketId, e);
            // The socket should have been closed by regSocket on other errors. If regSocket itself throws, it's likely during its own sc.close().
        } catch (Exception e) { // Catch any other unexpected runtime errors from regSocket
             log.error("Unexpected exception in ConAcceptor run for socketId: " + this.socketId, e);
             try {
                if (socketChannel.isOpen()) {
                    socketChannel.close();
                }
            } catch (IOException ex) {
                log.error("Failed to close socketChannel after unexpected exception in ConAcceptor for socketId: " + this.socketId, ex);
            }
        }
    }

    private void regSocket(SocketChannel sc) throws IOException {
        // The null check for targetReactor is now done in run()
        try {
            // The configureBlocking for the client socket (sc) should have been done in Acceptor.java
            // before submitting ConAcceptor to the executor.
            this.targetReactor.regNewSocket(sc, this.socketId, this.sslContext);
            log.debug("Socket with id " + this.socketId + " registered with IOReactor.");
        } catch (Exception e) { // Catching broader Exception from regNewSocket (e.g., ClosedChannelException)
            log.error("Failed to register new socket with IOReactor for socketId: " + this.socketId + ". Connection will be dropped.", e);
            try {
                if (sc.isOpen()) {
                    sc.close();
                }
            } catch (IOException ex) {
                log.error("Additionally, failed to close socketChannel after registration failure for socketId: " + this.socketId, ex);
                throw ex; // Propagate the close exception if closing itself fails critically
            }
            // Do not propagate original exception 'e' if socket close was successful,
            // as the primary issue (registration) is handled by logging and closing.
            // If sc.close() itself threw an IOException, it gets propagated.
        }
    }
}
