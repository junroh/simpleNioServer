package com.jun;

import com.jun.nioServer.NIOHttpService;
import com.jun.threadedServer.ThreadedServer;

public class Server {
    public static void main(String[] args) throws Exception {
        if (false) {
            ThreadedServer blockHttpService = new ThreadedServer(8080);
            blockHttpService.start();
            blockHttpService.waitStop();
        } else {
            NIOHttpService nioHttpService = new NIOHttpService(8080, false);
            nioHttpService.start();
            nioHttpService.waitStop();
        }
 }
}
