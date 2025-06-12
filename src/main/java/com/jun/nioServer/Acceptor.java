package com.jun.nioServer;

import com.jun.config.ServerConfig;
import com.jun.nioServer.utility.NamedThreadFactory;
import org.apache.log4j.Logger;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Acceptor extends Thread {

    private static final Logger log = Logger.getLogger(Acceptor.class);

    private final ExecutorService es;

    private ServerSocketChannel serverSocketChannel;
    private SSLContext sslContext;

    private IOReactor[] ioReactors;
    private int socketId;

    private Selector selector;

    public Acceptor(String address, int serverPort, boolean isSSL) throws Exception {
        InetAddress bindAddress = InetAddress.getByName(address);
        try {
            socketId = 0;
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress(bindAddress, serverPort), ServerConfig.NIO_ACCEPTOR_BACKLOG);

            if (isSSL) {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                    createKeyManagers(ServerConfig.SSL_KEYSTORE_PATH, ServerConfig.SSL_KEYSTORE_PASSWORD, ServerConfig.SSL_KEY_PASSWORD),
                    createTrustManagers(ServerConfig.SSL_TRUSTSTORE_PATH, ServerConfig.SSL_TRUSTSTORE_PASSWORD),
                    new SecureRandom());
            }

            if(!ServerConfig.NIO_ACCEPTOR_IS_BLOCKING) {
                setNonBlockingMode();
            }
            startIOReactor();
            log.info(String.format("Successfully bound to %s:%d", bindAddress.toString(), serverPort));

        } catch(UnknownHostException e) {
            log.error("Unknown host address " + bindAddress.toString());
        } catch (IOException e) {
            log.error("binding error on " + bindAddress.toString() + ":"+ serverPort);
        }
        setName(this.getClass().getSimpleName());
        es = Executors.newFixedThreadPool(ServerConfig.NIO_ACCEPTOR_NUM_IOREACTOR);
    }

    void stopThread() {
        try {
            log.info("Stopping acceptor");
            if(ioReactors!=null) {
                for(IOReactor ioReactor: ioReactors) {
                    ioReactor.stopThread();
                }
            }
            interrupt();
            serverSocketChannel.close();
            while(isAlive()) {}
            log.info("Stopped acceptor completely");
        } catch (IOException e) {
            log.error("Error on closing server " + e.toString());
        }
    }

    @Override
    public void run() {
        log.info("Started acceptor");
        while(!Thread.currentThread().isInterrupted()){
            try {
                if(ServerConfig.NIO_ACCEPTOR_IS_BLOCKING) {
                    SocketChannel socketChannel = serverSocketChannel.accept();
                    es.submit(new ConAcceptor(socketChannel));
                } else {
                    selector.select();
                    Set<SelectionKey> selected = selector.selectedKeys();
                    for (SelectionKey key : selected) {
                        if(key.isAcceptable()) {
                            Runnable r = (Runnable) (key.attachment());
                            if (r != null) {
                                es.submit(r);
                            }
                        }
                    }
                    selected.clear();
                }
            } catch(IOException e){
                log.error("IOException in Acceptor run loop", e);
            }
        }
        es.shutdown();
        log.info("Stopped acceptor");
    }

    private void setNonBlockingMode() throws  IOException {
        selector = Selector.open();
        serverSocketChannel.configureBlocking(false);
        SelectionKey sk = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
        sk.attach(new ConAcceptor(null));
    }

    private void startIOReactor() throws IOException {
        ExecutorService readerPool =
            Executors.newFixedThreadPool(ServerConfig.NIO_ACCEPTOR_NUM_READER_THREADS, new NamedThreadFactory("Reader"));
        ExecutorService writerPool =
            Executors.newFixedThreadPool(ServerConfig.NIO_ACCEPTOR_NUM_WRITER_THREADS, new NamedThreadFactory("Writer"));
        ioReactors = new IOReactor[ServerConfig.NIO_ACCEPTOR_NUM_IOREACTOR];
        for(int i=0;i< ioReactors.length; i++) {
            ioReactors[i] = new IOReactor(null, readerPool, writerPool);
            ioReactors[i].startThread();
        }
    }

    private KeyManager[] createKeyManagers(String filepath, String keystorePassword,
                                                 String keyPassword) {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (InputStream keyStoreIS = new FileInputStream(filepath)) {
                keyStore.load(keyStoreIS, keystorePassword.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyPassword.toCharArray());
            return kmf.getKeyManagers();
        } catch (Exception e) {
            log.error("Failed to create Key Manager" + e);
            return new KeyManager[0];
        }
    }

    private TrustManager[] createTrustManagers(String filepath, String keystorePassword) {
        try {
            KeyStore trustStore = KeyStore.getInstance("JKS");
            try (InputStream trustStoreIS = new FileInputStream(filepath)) {
                trustStore.load(trustStoreIS, keystorePassword.toCharArray());
            }
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustFactory.init(trustStore);
            return trustFactory.getTrustManagers();
        } catch (Exception e) {
            log.error("Failed to create Trust Manager" + e);
            return new TrustManager[0];
        }
    }

    private class ConAcceptor implements Runnable {
        private SocketChannel socketChannel;
        private int currentIOReactorIdx;

        ConAcceptor(SocketChannel socketChannel) {
            this.socketChannel = socketChannel;
            currentIOReactorIdx = 0;
        }

        public void run() {
            try {
                if(socketChannel==null) {
                    socketChannel = serverSocketChannel.accept();
                }
                regSocket(socketChannel);
            } catch (IOException ignore) {
                // do nothing
            }
        }

        private void regSocket(SocketChannel socketChannel) throws IOException {
            try {
                ioReactors[currentIOReactorIdx].
                    regNewSocket(socketChannel, socketId++, sslContext);
                currentIOReactorIdx = (currentIOReactorIdx+1)/ioReactors.length;
            } catch (Exception e) {
                log.error("Failed to register new socket. " +
                    "Connection is going to be dropped due to " + e.getLocalizedMessage());
                socketChannel.close();
            }
        }
    }
}
