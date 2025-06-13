package com.jun.nioServer;

import com.jun.config.ServerConfig; // Added import
import org.apache.log4j.Logger;

import javax.net.ssl.KeyManager; // Added import
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager; // Added import
import java.io.IOException; // Added import
import java.net.InetSocketAddress; // Added import
import java.nio.channels.Selector; // Added import
import java.nio.channels.ServerSocketChannel; // Added import
import java.util.concurrent.ExecutorService; // Added import
import java.util.concurrent.Executors; // Added import
import java.util.concurrent.TimeUnit; // Added import

public class NioServerService {

    protected static final Logger log = Logger.getLogger(NioServerService.class);

    // Configuration fields
    private final int port;
    private final String hostAddress;
    private final boolean isSslEnabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keyPassword;
    private final String truststorePath;
    private final String truststorePassword;
    private final int numIoReactors;
    private final int numReaderThreads;
    private final int numWriterThreads;
    private final int acceptorBacklog;
    private final boolean isAcceptorNonBlocking; // True if Acceptor should use non-blocking ServerSocketChannel for accept()

    // Managed resources
    private SSLContext sslContext;
    private IOReactor[] ioReactors;
    private Selector[] ioReactorSelectors;
    private ExecutorService conAcceptorExecutorService;
    private ServerSocketChannel serverSocketChannel;
    private IAcceptor acceptorInstance;
    private Thread acceptorThread; // Existing field

    public NioServerService(ServerConfig config) { // Assuming direct access to ServerConfig static fields for setup
        this.port = ServerConfig.NIO_SERVER_PORT;
        this.hostAddress = ServerConfig.NIO_ACCEPTOR_ADDRESS;
        this.isSslEnabled = ServerConfig.NIO_SERVER_SSL_ENABLED;
        this.keystorePath = ServerConfig.SSL_KEYSTORE_PATH;
        this.keystorePassword = ServerConfig.SSL_KEYSTORE_PASSWORD;
        this.keyPassword = ServerConfig.SSL_KEY_PASSWORD;
        this.truststorePath = ServerConfig.SSL_TRUSTSTORE_PATH;
        this.truststorePassword = ServerConfig.SSL_TRUSTSTORE_PASSWORD;
        this.numIoReactors = ServerConfig.NIO_ACCEPTOR_NUM_IOREACTOR;
        this.numReaderThreads = ServerConfig.NIO_ACCEPTOR_NUM_READER_THREADS;
        this.numWriterThreads = ServerConfig.NIO_ACCEPTOR_NUM_WRITER_THREADS;
        this.acceptorBacklog = ServerConfig.NIO_ACCEPTOR_BACKLOG;
        // Acceptor's 'isNonBlocking' parameter means its internal select loop for accept events.
        // ServerConfig.NIO_ACCEPTOR_IS_BLOCKING refers to serverSocketChannel.configureBlocking().
        // If NIO_ACCEPTOR_IS_BLOCKING is true, Acceptor's isNonBlocking should be false (traditional blocking accept)
        // If NIO_ACCEPTOR_IS_BLOCKING is false, Acceptor's isNonBlocking should be true (selector-based accept)
        this.isAcceptorNonBlocking = !ServerConfig.NIO_ACCEPTOR_IS_BLOCKING;
    }

    private boolean internalCreateSslContext() throws Exception {
        if (!this.isSslEnabled) {
            log.info("SSL is disabled for NIO server.");
            this.sslContext = null;
            return true;
        }
        log.info("SSL is enabled for NIO server. Initializing SSLContext...");
        try {
            KeyManager[] keyManagers = Acceptor.createKeyManagers(this.keystorePath, this.keystorePassword, this.keyPassword);
            TrustManager[] trustManagers = Acceptor.createTrustManagers(this.truststorePath, this.truststorePassword);
            this.sslContext = SSLContext.getInstance("TLS");
            this.sslContext.init(keyManagers, trustManagers, null);
            log.info("SSLContext initialized successfully.");
            return true;
        } catch (Exception e) {
            log.error("Failed to initialize SSLContext for NIO server.", e);
            throw e; // Propagate to indicate critical failure
        }
    }

    private boolean internalCreateAndStartIoReactors() {
        log.info("Initializing IOReactors: " + this.numIoReactors + " instance(s).");
        this.ioReactors = new IOReactor[this.numIoReactors];
        this.ioReactorSelectors = new Selector[this.numIoReactors];
        for (int i = 0; i < this.numIoReactors; i++) {
            try {
                this.ioReactorSelectors[i] = Selector.open();
                ExecutorService readerPool = Executors.newFixedThreadPool(this.numReaderThreads);
                ExecutorService writerPool = Executors.newFixedThreadPool(this.numWriterThreads);
                this.ioReactors[i] = new IOReactor(this.ioReactorSelectors[i], readerPool, writerPool);
                this.ioReactors[i].startThread();
                log.info("IOReactor " + i + " initialized and started.");
            } catch (IOException e) {
                log.error("Failed to initialize IOReactor " + i + ".", e);
                // Clean up already initialized reactors and selectors up to this point
                for (int j = 0; j < i; j++) {
                    if (this.ioReactors[j] != null) this.ioReactors[j].stopThread();
                    if (this.ioReactorSelectors[j] != null && this.ioReactorSelectors[j].isOpen()) {
                        try { this.ioReactorSelectors[j].close(); } catch (IOException ioe) { log.error("Error closing selector for IOReactor " + j, ioe); }
                    }
                }
                return false; // Indicate failure
            }
        }
        return true; // Indicate success
    }

    private void internalCreateConAcceptorExecutorService() {
        log.info("Creating ExecutorService for ConAcceptors...");
        this.conAcceptorExecutorService = Executors.newCachedThreadPool();
    }

    private boolean internalCreateServerSocketChannel() {
        log.info("Setting up ServerSocketChannel for Acceptor...");
        try {
            this.serverSocketChannel = ServerSocketChannel.open();
            this.serverSocketChannel.bind(new InetSocketAddress(this.hostAddress, this.port), this.acceptorBacklog);
            log.info("ServerSocketChannel bound to " + this.hostAddress + ":" + this.port + " with backlog " + this.acceptorBacklog);
            return true;
        } catch (IOException e) {
            log.error("Failed to create/bind ServerSocketChannel.", e);
            this.serverSocketChannel = null; // Ensure it's null on failure
            return false;
        }
    }

    private void internalCreateAcceptor() throws IOException {
        log.info("Initializing Acceptor. Acceptor non-blocking mode: " + this.isAcceptorNonBlocking);
        this.acceptorInstance = new Acceptor(
                this.serverSocketChannel,
                this.conAcceptorExecutorService,
                this.ioReactors,
                this.sslContext,
                this.isAcceptorNonBlocking
        );
        log.info("Acceptor initialized: " + this.acceptorInstance.getClass().getName());
    }


    // start(), stop(), waitStop() will be refactored in next step to use these internal methods.
    // For now, keeping existing start() as a placeholder to ensure class compiles,
    // though it uses the old acceptorInstance injection logic.
    // The old constructor will be removed by the new config-based one.

    public void start() throws Exception {
        log.info("NioServerService.start() called.");
        if (this.acceptorThread != null && this.acceptorThread.isAlive()) {
            log.warn("Service acceptor thread is already running.");
            return;
        }

        // Initialize all resources
        if (!internalCreateSslContext()) { // Throws exception on critical SSL failure
             log.fatal("NIO server startup failed during SSLContext creation.");
             throw new RuntimeException("SSLContext creation failed."); // Or specific exception
        }
        if (!internalCreateAndStartIoReactors()) {
            log.fatal("NIO server startup failed during IOReactor creation.");
            // No SSL context to clean if it was created, as it's just an object.
            // internalCreateAndStartIoReactors handles its own partial cleanup.
            throw new RuntimeException("IOReactor creation failed.");
        }
        internalCreateConAcceptorExecutorService();
        if (!internalCreateServerSocketChannel()) {
             log.fatal("NIO server startup failed during ServerSocketChannel creation.");
             shutdownNioResources(); // Clean up what was created
             throw new RuntimeException("ServerSocketChannel creation failed.");
        }
        internalCreateAcceptor(); // Throws IOException

        // Start the acceptor thread
        this.acceptorThread = new Thread(this.acceptorInstance); // acceptorInstance is now created internally
        this.acceptorThread.setDaemon(false);

        String acceptorClassName = this.acceptorInstance.getClass().getSimpleName();
        if (acceptorClassName.isEmpty() && this.acceptorInstance instanceof Thread) {
            acceptorClassName = ((Thread) this.acceptorInstance).getName();
        } else if (acceptorClassName.isEmpty()) {
            acceptorClassName = "IAcceptor";
        }
        this.acceptorThread.setName(acceptorClassName + "-ServiceThread");

        log.info("Starting service acceptor thread: " + this.acceptorThread.getName());
        this.acceptorThread.start();
    }

    public void waitStop() throws InterruptedException {
        if (this.acceptorThread != null) {
            log.info("Waiting for service acceptor thread (" + this.acceptorThread.getName() + ") to stop...");
            this.acceptorThread.join();
            log.info("Service acceptor thread (" + this.acceptorThread.getName() + ") has stopped.");
        } else {
            log.info("No service acceptor thread to wait for.");
        }
    }

    public void stop() {
        log.info("NioServerService.stop() called.");
        if (this.acceptorInstance != null) {
            log.info("Calling stopThread() on internal acceptor instance...");
            this.acceptorInstance.stopThread();
        }

        if (this.acceptorThread != null && this.acceptorThread.isAlive()) {
            log.info("Waiting for service acceptor thread (" + this.acceptorThread.getName() + ") to terminate...");
            try {
                this.acceptorThread.join(5000);
                if (this.acceptorThread.isAlive()) {
                    log.warn("Service acceptor thread (" + this.acceptorThread.getName() + ") did not stop gracefully after 5s. Interrupting...");
                    this.acceptorThread.interrupt();
                    this.acceptorThread.join(1000);
                    if (this.acceptorThread.isAlive()) {
                        log.error("Service acceptor thread (" + this.acceptorThread.getName() + ") failed to stop even after interrupt.");
                    }
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for service acceptor thread (" + this.acceptorThread.getName() + ") to stop.", e);
                Thread.currentThread().interrupt();
            }
        }

        // After acceptor stops, shutdown other resources
        shutdownNioResources();
        log.info("NioServerService stop sequence complete.");
    }

    private void shutdownNioResources() {
        log.info("Shutting down ConAcceptor ExecutorService...");
        if (this.conAcceptorExecutorService != null) {
            this.conAcceptorExecutorService.shutdown();
            try {
                if (!this.conAcceptorExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    this.conAcceptorExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                this.conAcceptorExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("ConAcceptor ExecutorService shut down.");
        }

        log.info("Shutting down IOReactors...");
        if (this.ioReactors != null) {
            for (int i = 0; i < this.ioReactors.length; i++) {
                if (this.ioReactors[i] != null) {
                    log.info("Stopping IOReactor " + i);
                    this.ioReactors[i].stopThread();
                }
            }
        }
        if (this.ioReactorSelectors != null) {
            for (int i = 0; i < this.ioReactorSelectors.length; i++) {
                if (this.ioReactorSelectors[i] != null && this.ioReactorSelectors[i].isOpen()) {
                    try {
                        log.info("Closing selector for IOReactor " + i);
                        this.ioReactorSelectors[i].close();
                    } catch (IOException e) {
                        log.error("Error closing selector for IOReactor " + i, e);
                    }
                }
            }
        }
        log.info("IOReactors and their selectors shut down.");

        if (this.serverSocketChannel != null && this.serverSocketChannel.isOpen()) {
            log.info("Closing ServerSocketChannel...");
            try {
                this.serverSocketChannel.close();
                log.info("ServerSocketChannel closed.");
            } catch (IOException e) {
                log.error("Error closing ServerSocketChannel.", e);
            }
        }
        // sslContext does not need explicit closing
    }
}
