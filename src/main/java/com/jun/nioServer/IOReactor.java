package com.jun.nioServer;

import com.jun.nioServer.handler.MsgHandler;
import com.jun.nioServer.handler.OnCompleteListener;
import com.jun.nioServer.handler.SocketReadHandler;
import com.jun.nioServer.handler.SocketWriteHandler;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;


// socketchannel - always writable. readable is set when data is on channel.
// important thing to set writable is writable data is ready. Then clear bit if sending data completes
public class IOReactor implements Runnable {

    private static final Logger log = Logger.getLogger(IOReactor.class);

    private final Selector selector;
    private final Object selectorLock = new Object();
    private final MsgHandler msgHandler;
    private final ExecutorService readerPool;
    private final ExecutorService writerPool;
    private final Thread thread;

    public IOReactor(Selector givenSelector,
                     ExecutorService readerPool, ExecutorService writerPool) throws IOException {
        if(givenSelector==null) {
            selector = Selector.open();
        } else {
            selector = givenSelector;
        }
        this.msgHandler = new MsgHandler();
        this.readerPool = readerPool;
        this.writerPool = writerPool;
        this.thread = new Thread(this, this.getClass().getSimpleName());
    }

    void startThread() {
        msgHandler.start();
        thread.start();
    }

    void stopThread() {
        log.info("Stopping Request MsgProcessor");
        readerPool.shutdown();
        msgHandler.stop();
        writerPool.shutdown();
        if(thread !=null) {
            thread.interrupt();
            try {
                thread.join();
            } catch (Exception ignore) {

            }
        }
        log.info("Stopped Request MsgProcessor completely");
    }

    @Override
    public void run() {
        log.info("IOReactor started");
        while(!Thread.currentThread().isInterrupted()){
            try {
                synchronized (selectorLock) {
                    // Do nothing. This ensures a registration of socket
                }
                log.trace("wait-on");
                selector.select(1);
                log.trace("wait-off");
                // todo: think different way - one selector with accept + read + write
                // https://blog.genuine.com/2013/07/nio-based-reactor/
                // http://gee.cs.oswego.edu/dl/cpjslides/nio.pdf
                Set<SelectionKey> selected = selector.selectedKeys();
                for (SelectionKey key : selected) {
                    ConnectedSocket socket = (ConnectedSocket) (key.attachment());
                    log.debug("ready key " + key.readyOps() + " on sock " + socket.getSocketId());
                    if (key.isReadable()) {
                        onRead(socket);
                    } else if (key.isWritable()) {
                        onWrite(socket);
                    } else {
                        log.error("Unknown key " +
                            (key.readyOps() & ~(SelectionKey.OP_READ|SelectionKey.OP_WRITE)));
                    }
                }
                selected.clear();
            } catch(IOException e) {
                log.error("IoException " + e, e);
            } catch (Exception e) {
                log.error("Unknown Exception " + e, e);
            }
        }
        log.info("IOReactor stopped");
    }

    public void regNewSocket(SocketChannel newSocketChannel, int socketId, SSLContext sslContext) throws IOException {
        log.debug("Registering socket #" + newSocketChannel.hashCode() + " as id " + socketId);
        ConnectedSocket connectedSocket = new ConnectedSocket(socketId, newSocketChannel, sslContext);
        SelectionKey key;
        // so wakeup is required. However, this can solve 100% when selector loop runs faster than this
        // execution. In that case, blocking(in selector) -> wakeup -> blocking(in selector) -> register
        // therefore, another lock is added.
        synchronized (selectorLock) {
            log.trace("wakeup selector for " + socketId);
            selector.wakeup();
            key = newSocketChannel.register(selector, 0);
        }
        connectedSocket.configKey(key);
        log.debug("Registered socket #" + newSocketChannel.hashCode() + " as id " + socketId);
    }

    private void onRead(ConnectedSocket socket) {
        socket.clrInterestedOps(SelectionKey.OP_READ);
        OnCompleteListener listener = new OnCompleteListener() {
            @Override
            public void onComplete(int len, List<ByteBuffer> datas) {
                log.debug("read complete on socketid " + socket.getSocketId() + " len " + len);
                if (len > 0) {
                    for(ByteBuffer data: datas) {
                        socket.addSocketReadData(data);
                    }
                    msgHandler.enqueue(socket);
                }
            }
            @Override
            public void onException(Exception e) {
                log.warn("Error on reading " + e +
                    " on socketid " + socket.getSocketId() + ". Socket has been closing.");
                socket.close();
            }
        };
        readerPool.submit(new SocketReadHandler(socket, listener));
    }

    private void onWrite(ConnectedSocket socket) {
        socket.clrInterestedOps(SelectionKey.OP_WRITE);
        OnCompleteListener listener = new OnCompleteListener() {
            @Override
            public void onComplete(int len, List<ByteBuffer> datas) {
                log.debug("write complete on socketid " + socket.getSocketId() + " len " + len);
            }
            @Override
            public void onException(Exception e) {
                log.warn("Error on writing " + e +
                    " on socketid " + socket.getSocketId() + ". Socket has been closing.");
                socket.close();
            }
        };
        writerPool.submit(new SocketWriteHandler(socket, listener));
    }
}
