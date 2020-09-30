package com.jun.threadedServer;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class Worker implements Runnable{

    private static final Logger log = Logger.getLogger(Worker.class);

    protected Socket clientSocket = null;
    protected String serverText   = null;

    public Worker(Socket clientSocket, String serverText) {
        this.clientSocket = clientSocket;
        this.serverText   = serverText;
    }

    public void run() {
        try {
            InputStream input  = clientSocket.getInputStream();
            OutputStream output = clientSocket.getOutputStream();
            long time = System.currentTimeMillis();
            output.write(("HTTP/1.1 200 OK\n\nWorkerRunnable: " +
                this.serverText + " - " +
                time +
                "").getBytes());
            output.close();
            input.close();
            clientSocket.close();
            log.debug("Request processed: " + time);
        } catch (IOException e) {
            //report exception somewhere.
            e.printStackTrace();
        }
    }
}