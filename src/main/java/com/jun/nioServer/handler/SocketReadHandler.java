package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

public class SocketReadHandler implements Runnable {

    private static final Logger log = Logger.getLogger(SocketReadHandler.class);

    private final ConnectedSocket socket;
    private final OnCompleteListener listener;

    public SocketReadHandler(ConnectedSocket socket, OnCompleteListener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    @Override
    public void run() {
        // If multiple threads are reading data from particular socket, only one thread wins
        if(!socket.tryReadLock()) {
            return;
        }
        try {
            int totBytes = 0;
            int readbytes;
            List<ByteBuffer> socketDatas = new LinkedList<>();
            do {
                ByteBuffer readByteBuffer = ByteBuffer.allocate(4 * 1024);    // 4K
                readbytes = readSocket(socket, readByteBuffer);
                if (readbytes > 0) {
                    readByteBuffer.flip();
                    socketDatas.add(readByteBuffer);
                }
                totBytes += readbytes;
            } while (readbytes > 0);
            // check socket closed
            if (!socket.isClosed()) {
                listener.onComplete(totBytes, socketDatas);
                socket.addInterestedOps(SelectionKey.OP_READ);   // enable read
            }
        } catch (IOException e) {
            listener.onException(e);
        } finally {
            socket.unLockRead();
        }
    }

    private int readSocket(ConnectedSocket socket, ByteBuffer readByteBuffer) throws IOException {
        int totalBytesRead = 0;
        int bytesRead;
        SocketChannel socketChannel = socket.getSocketChannel();
        do {
            bytesRead = socketChannel.read(readByteBuffer);
            if(bytesRead <= 0) {
                break;
            }
            totalBytesRead += bytesRead;
        }while(readByteBuffer.hasRemaining());
        // todo reach to end of stream -> connection is closed already
        if(bytesRead == -1) {
            socket.close();
        }
        return totalBytesRead;
    }
}
