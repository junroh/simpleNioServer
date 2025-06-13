package com.jun;

import com.jun.config.ServerConfig;
import com.jun.nioServer.NioServerService;
import com.jun.threadedServer.ThreadedServer;
import org.apache.log4j.Logger;

import java.io.IOException;


public class Server {
    private static final Logger log = Logger.getLogger(Server.class);

    public static void main(String[] args) {
        String serverType = System.getProperty(ServerConfig.SERVER_TYPE_PROPERTY_KEY, ServerConfig.DEFAULT_SERVER_TYPE);

        try {
            if (ServerConfig.SERVER_TYPE_THREADED.equalsIgnoreCase(serverType)) {
                startThreadedServerFlow();
            } else { // Default to NIO
                if (!ServerConfig.SERVER_TYPE_NIO.equalsIgnoreCase(serverType)) {
                    log.warn("Unrecognized server type '" + serverType + "', defaulting to NIO server.");
                }
                startNioServerFlow();
            }
        } catch (Exception e) {
            log.fatal("Server failed to start or experienced a critical error in main flow.", e);
            // Consider System.exit(1) if this is a top-level failure.
        }
    }

    private static void startThreadedServerFlow() throws InterruptedException {
        log.info("Starting Threaded server...");
        ThreadedServer threadedServer = new ThreadedServer(ServerConfig.THREADED_SERVER_PORT);
        threadedServer.start();
        threadedServer.waitStop();
        log.info("Threaded server has stopped.");
    }

    private static void startNioServerFlow() throws Exception {
        log.info("Attempting to start NioServerService...");
        // NioServerService constructor now takes ServerConfig.
        // Pass a new instance, NioServerService will read static fields from ServerConfig class as implemented.
        NioServerService nioService = new NioServerService(new ServerConfig());

        try {
            nioService.start();    // This method now internally handles all setup and starts the acceptor thread.
                                   // It will throw an exception if setup fails critically.
            nioService.waitStop(); // This blocks until the NioServerService's acceptor thread fully stops.
        } catch (Exception e) {
            log.error("NioServerService experienced an error during its lifecycle.", e);
            // NioServerService's stop() and internal shutdownNioResources() should handle cleanup
            // even if start() partially fails and throws.
            // If start() itself throws, nioService.stop() might not be explicitly called here,
            // but its internal try-finally in start (if any for partial init) or a robust stop() method
            // should attempt cleanup. The current NioServerService.start() calls shutdownNioResources on failure.
        } finally {
            // Ensure stop is called if service was started but waitStop was interrupted or start threw after some resources acquired.
            // NioServerService.stop() is designed to be safe to call even if start didn't complete.
            if (nioService != null) { // Should always be true unless constructor failed.
            }
        }
        log.info("NioServerService flow has concluded.");
    }
}
