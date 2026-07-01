package com.jun;

import com.jun.app.DefaultRouter;
import com.jun.config.ServerConfig;
import com.jun.nioServer.NioServerService;
import com.jun.threadedServer.ThreadedServer;
import org.apache.log4j.Logger;

public class Server {
    private static final Logger log = Logger.getLogger(Server.class);

    public static void main(String[] args) {
        ServerConfig cfg = ServerConfig.defaults();
        String serverType = cfg.getServerType();

        try {
            if (ServerConfig.TYPE_THREADED.equalsIgnoreCase(serverType)) {
                startThreadedServer(cfg, false);
            } else if (ServerConfig.TYPE_VIRTUAL.equalsIgnoreCase(serverType)) {
                startThreadedServer(cfg, true);
            } else if (ServerConfig.TYPE_NIO.equalsIgnoreCase(serverType)) {
                startNioServer(cfg);
            } else {
                log.warn("Unrecognized server type '" + serverType + "', defaulting to NIO.");
            }
        } catch (Exception e) {
            log.fatal("Server failed to start.", e);
        }
    }

    private static void startThreadedServer(ServerConfig cfg, boolean useVirtualThreads) throws InterruptedException {
        ThreadedServer server = ThreadedServer.fromConfig(cfg, DefaultRouter.fromConfig(cfg), useVirtualThreads);
        server.start();
        server.waitStop();
    }

    private static void startNioServer(ServerConfig cfg) throws Exception {
        NioServerService service = new NioServerService(cfg);
        try {
            service.start();
            service.waitStop();
        } catch (Exception e) {
            service.stop();
            throw e;
        }
    }
}
