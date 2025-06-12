package com.jun;

import com.jun.config.ServerConfig;
import com.jun.nioServer.NIOHttpService;
import com.jun.threadedServer.ThreadedServer;
import org.apache.log4j.Logger;

public class Server {
    private static final Logger log = Logger.getLogger(Server.class);
    public static void main(String[] args) throws Exception {
        String serverType = System.getProperty(ServerConfig.SERVER_TYPE_PROPERTY_KEY, ServerConfig.DEFAULT_SERVER_TYPE);

        if (ServerConfig.SERVER_TYPE_THREADED.equalsIgnoreCase(serverType)) {
            log.info("Starting Threaded server...");
            ThreadedServer blockHttpService = new ThreadedServer(ServerConfig.THREADED_SERVER_PORT);
            blockHttpService.start();
            blockHttpService.waitStop();
        } else { // Default to NIO
            if (!ServerConfig.SERVER_TYPE_NIO.equalsIgnoreCase(serverType)) {
                // Log a warning if an unrecognized value was provided, but still defaulting to NIO
                log.warn("Unrecognized server type '" + serverType + "', defaulting to NIO server.");
            }
            log.info("Starting NIO server...");
            NIOHttpService nioHttpService = new NIOHttpService(ServerConfig.NIO_SERVER_PORT, ServerConfig.NIO_SERVER_SSL_ENABLED);
            nioHttpService.start();
            nioHttpService.waitStop();
        }
    }
}
