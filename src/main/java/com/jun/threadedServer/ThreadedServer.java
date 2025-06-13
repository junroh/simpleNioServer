package com.jun.threadedServer;

import com.jun.config.ServerConfig;
import com.jun.http.HttpRequestHandler;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadedServer implements Runnable{

    private static final Logger log = Logger.getLogger(ThreadedServer.class);
    private static final int BACKLOG = ServerConfig.THREADED_SERVER_BACKLOG;

    protected int          serverPort   = ServerConfig.THREADED_SERVER_PORT;
    protected ServerSocket serverSocket = null;
    protected boolean      isStopped    = false;
    protected ExecutorService threadPool = Executors.newFixedThreadPool(ServerConfig.THREADED_SERVER_POOL_SIZE);
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
            HttpRequestHandler handler = new SimpleThreadedHttpRequestHandler();
            this.threadPool.submit(
                new Worker(clientSocket,
                    "Thread Pooled Server", handler));
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

    public synchronized boolean isStopped() { // Changed from private to public
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
            throw new RuntimeException("Cannot open port " + this.serverPort, e);
        }
    }
}