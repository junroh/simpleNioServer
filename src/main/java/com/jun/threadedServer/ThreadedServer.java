package com.jun.threadedServer;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadedServer implements Runnable{

    private static final Logger log = Logger.getLogger(ThreadedServer.class);
    private static final int BACKLOG = 1024;

    protected int          serverPort   = 8080;
    protected ServerSocket serverSocket = null;
    protected boolean      isStopped    = false;
    protected ExecutorService threadPool = Executors.newFixedThreadPool(100);
    private Thread server;

    public ThreadedServer(int port){
        this.serverPort = port;
    }

    public void run() {
        openServerSocket();
        log.info("Server Started.") ;
        while(!isStopped()){
            Socket clientSocket = null;
            try {
                clientSocket = this.serverSocket.accept();
            } catch (IOException e) {
                if(isStopped()) {
                    log.error("Server Stopped.") ;
                    break;
                }
                throw new RuntimeException(
                    "Error accepting client connection", e);
            }
            this.threadPool.submit(
                new Worker(clientSocket,
                    "Thread Pooled Server"));
        }
        this.threadPool.shutdown();
        log.info("Server Stopped.") ;
    }

    public synchronized void start() {
        server = new Thread(this, this.getClass().getSimpleName());
        server.start();
    }

    public void waitStop() throws InterruptedException {
        server.join();
    }

    private synchronized boolean isStopped() {
        return isStopped;
    }

    public synchronized void stop(){
        if(isStopped) {
            return;
        }
        server.interrupt();
        isStopped = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            throw new RuntimeException("Error closing server", e);
        }
    }

    private void openServerSocket() {
        try {
            this.serverSocket = new ServerSocket(this.serverPort, BACKLOG);
        } catch (IOException e) {
            throw new RuntimeException("Cannot open port 8080", e);
        }
    }
}