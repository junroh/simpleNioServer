package com.jun.threadedServer;

import com.jun.http.HttpRequestHandler;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class Worker implements Runnable{

    private static final Logger log = Logger.getLogger(Worker.class);

    protected Socket clientSocket = null;
    protected String serverText   = null;
    private final HttpRequestHandler requestHandler;

    public Worker(Socket clientSocket, String serverText, HttpRequestHandler requestHandler) {
        this.clientSocket = clientSocket;
        this.serverText   = serverText;
        this.requestHandler = requestHandler;
    }

    public void run() {
        InputStream input = null;
        OutputStream output = null;
        try {
            input  = clientSocket.getInputStream();
            output = clientSocket.getOutputStream();

            this.requestHandler.handle(input, output, this.serverText); // Call the handler

        } catch (IOException e) {
            log.error("Error processing client request for socket: " + clientSocket, e);
        } finally {
            try {
                if (output != null) output.close();
            } catch (IOException e) {
                log.warn("Error closing output stream for socket: " + clientSocket, e);
            }
            try {
                if (input != null) input.close();
            } catch (IOException e) {
                log.warn("Error closing input stream for socket: " + clientSocket, e);
            }
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                log.warn("Error closing client socket: " + clientSocket, e);
            }
            log.debug("Request processed and resources closed for client: " + (clientSocket != null ? clientSocket.getInetAddress() : "unknown"));
        }
    }
}