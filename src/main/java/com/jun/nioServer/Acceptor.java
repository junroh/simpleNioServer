package com.jun.nioServer;

import com.jun.config.ServerConfig;
import org.apache.log4j.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class Acceptor extends Thread implements IAcceptor {

    private static final Logger log = Logger.getLogger(Acceptor.class);

    private final ServerSocketChannel serverSocketChannel; // Injected
    private final ExecutorService conAcceptorExecutor;   // Injected
    private final IOReactor[] ioReactors;                // Injected
    private final SSLContext sslContext;                 // Injected (can be null)
    private final boolean isNonBlocking;
    private final String localAddressString;

    private Selector selector; // Used only in non-blocking mode
    private volatile boolean running = true;

    private int currentIOReactorIndex = 0;
    private final AtomicInteger socketIdCounter = new AtomicInteger(0);

    public Acceptor(ServerSocketChannel serverSocketChannel,
                      ExecutorService conAcceptorExecutor,
                      IOReactor[] ioReactors,
                      SSLContext sslContext, // Can be null for non-SSL
                      boolean isNonBlocking) throws IOException {
        this.serverSocketChannel = serverSocketChannel;
        this.conAcceptorExecutor = conAcceptorExecutor;
        this.ioReactors = ioReactors;
        this.sslContext = sslContext; // May be null
        this.isNonBlocking = isNonBlocking;

        if (this.ioReactors == null || this.ioReactors.length == 0) {
            throw new IllegalArgumentException("IOReactors array cannot be null or empty.");
        }
        if (this.conAcceptorExecutor == null) {
            throw new IllegalArgumentException("ExecutorService for ConAcceptor cannot be null.");
        }
        if (this.serverSocketChannel == null) {
            throw new IllegalArgumentException("ServerSocketChannel cannot be null.");
        }

        if (isNonBlocking) {
            this.serverSocketChannel.configureBlocking(false);
            this.selector = Selector.open();
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        }
        this.localAddressString = serverSocketChannel.getLocalAddress().toString(); // Cache local address

        setName(getClass().getSimpleName() + "-" + this.localAddressString);
        log.info(String.format("Acceptor initialized for %s. Non-blocking: %s", this.localAddressString, isNonBlocking));
    }

    @Override
    public void stopThread() {
        log.info("Stopping Acceptor for " + this.localAddressString);
        running = false;

        if (selector != null && selector.isOpen()) {
            log.debug("Waking up and closing selector for " + this.localAddressString);
            selector.wakeup();
            try {
                selector.close();
                log.info("Selector closed for " + this.localAddressString);
            } catch (IOException e) {
                log.error("IOException while closing selector in Acceptor for " + this.localAddressString, e);
            }
        } else {
            log.debug("Selector already null or closed for " + this.localAddressString);
        }
        log.info("Acceptor stopThread() called for " + this.localAddressString + ". Flag 'running' set to false and selector woken up/closed if applicable.");
    }

    @Override
    public void run() {
        log.info("Acceptor started for " + this.localAddressString);
        while (running && !Thread.currentThread().isInterrupted()) {
            SocketChannel clientSocket = null; // Moved out to be accessible for the common processing block
            try {
                if (isNonBlocking) {
                    int selectedCount = selector.select(); // Blocking call
                    if (Thread.currentThread().isInterrupted()) { // Check interrupt status after select()
                        log.warn("Acceptor thread interrupted in non-blocking select loop (after select).");
                        running = false; // Ensure loop termination
                        break;
                    }
                    if (!running) { // Check running status after select()
                        break;
                    }
                    // Selected count can be 0 if selector was woken up by stopThread() -> selector.wakeup()
                    // and running became false. In this case, clientSocket remains null and loop should exit.
                    if (selectedCount > 0) {
                        Set<SelectionKey> selectedKeys = selector.selectedKeys();
                        for (SelectionKey key : selectedKeys) {
                            if (key.isValid() && key.isAcceptable()) {
                                clientSocket = serverSocketChannel.accept();
                                if (clientSocket != null) {
                                    // Common processing logic will handle this clientSocket
                                    break; // Process one accepted socket per select() iteration
                                }
                            }
                        }
                        selectedKeys.clear();
                    }
                } else { // Blocking mode
                    clientSocket = serverSocketChannel.accept(); // Blocking call
                    if (Thread.currentThread().isInterrupted()) { // Check interrupt status after accept()
                         log.warn("Acceptor thread interrupted in blocking accept loop (after accept).");
                         running = false; // Ensure loop termination
                         if(clientSocket != null) try { clientSocket.close(); } catch (IOException e) { log.error("Error closing client socket after interrupt", e); }
                         break;
                    }
                }

                if (clientSocket != null) {
                    clientSocket.configureBlocking(ServerConfig.CLIENT_SOCKET_BLOCKING_MODE);

                    int nextSocketId = socketIdCounter.getAndIncrement();
                    IOReactor targetIoReactor = ioReactors[currentIOReactorIndex];
                    currentIOReactorIndex = (currentIOReactorIndex + 1) % ioReactors.length;

                    log.debug("Accepted connection for socket ID " + nextSocketId + ", routing to IOReactor " + currentIOReactorIndex);
                    // Assuming ConAcceptor constructor will be: ConAcceptor(SocketChannel, IOReactor, SSLContext, int)
                    conAcceptorExecutor.submit(new ConAcceptor(clientSocket, targetIoReactor, sslContext, nextSocketId));
                }

            } catch (java.nio.channels.ClosedByInterruptException e) {
                log.warn("Acceptor thread interrupted (ClosedByInterruptException) in run loop for " + this.localAddressString + ". Shutting down.", e);
                running = false;
            } catch (java.nio.channels.AsynchronousCloseException e) {
                log.warn("ServerSocketChannel closed asynchronously (AsynchronousCloseException) in Acceptor run loop for " + this.localAddressString + ". Shutting down.", e);
                running = false;
            } catch (java.nio.channels.ClosedChannelException e) {
                log.warn("Channel closed (ClosedChannelException) in Acceptor run loop for " + this.localAddressString + ". Shutting down.", e);
                running = false;
            } catch (IOException e) {
                if (!running || (selector != null && !selector.isOpen()) || !serverSocketChannel.isOpen()) {
                     log.warn("Acceptor for " + this.localAddressString + " shutting down or channel/selector closed: " + e.getMessage());
                } else {
                    log.error("IOException in Acceptor run loop for " + this.localAddressString, e);
                }
            } catch (Exception e) {
                if (running) {
                    log.error("Unexpected exception in Acceptor run loop for " + this.localAddressString, e);
                }
            }
        }
        log.info("Acceptor thread finished for " + this.localAddressString);
    }

    public static KeyManager[] createKeyManagers(String filepath, String keystorePassword, String keyPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream keyStoreIS = new FileInputStream(filepath)) {
            keyStore.load(keyStoreIS, keystorePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword.toCharArray());
        return kmf.getKeyManagers();
    }

    public static TrustManager[] createTrustManagers(String filepath, String keystorePassword) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream trustStoreIS = new FileInputStream(filepath)) {
            trustStore.load(trustStoreIS, keystorePassword.toCharArray());
        }
        TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init(trustStore);
        return trustFactory.getTrustManagers();
    }
}
