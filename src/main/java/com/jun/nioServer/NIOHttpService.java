package com.jun.nioServer;

import org.apache.log4j.Logger;

public class NIOHttpService {

    protected static final Logger log = Logger.getLogger(NIOHttpService.class);

    private final int port;
    private final boolean ssl;
    private Acceptor acceptorThread;

    public NIOHttpService(int port, boolean ssl) {
        this.port = port;
        this.ssl = ssl;
    }

    public void start() throws Exception {
        acceptorThread = new Acceptor("localhost", port, ssl);
        acceptorThread.setDaemon(false);
        acceptorThread.start();
    }

    public void waitStop() throws InterruptedException {
        acceptorThread.join();
    }

    public void stop() {
        if (acceptorThread != null) {
            acceptorThread.stopThread();
            while(acceptorThread.isAlive()){}
        }
        log.info("Stopped Nonblocking");
    }
}
