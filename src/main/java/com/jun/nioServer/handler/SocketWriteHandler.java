package com.jun.nioServer.handler;

import com.jun.nioServer.ConnectedSocket;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

public class SocketWriteHandler implements Runnable {

    private static final Logger log = Logger.getLogger(SocketWriteHandler.class);
    private final ConnectedSocket socket;
    private final OnCompleteListener listener;

    public SocketWriteHandler(ConnectedSocket socket, OnCompleteListener listener) {
        this.socket = socket;
        this.listener = listener;
    }

    @Override
    public void run() {
        // If multiple threads are writing data from particular socket, only one thread wins
        if(!socket.tryWriteLock()) {
            return;
        }
        try {
            List<ByteBuffer> readyBuffers = socket.getWritebuffers();
            Iterator<ByteBuffer> itr = readyBuffers.iterator();
            int totWrite = 0;
            while (itr.hasNext()) {
                ByteBuffer writebuffer = itr.next();
                int bytewrite = write(socket.getSocketChannel(), writebuffer);
                totWrite += bytewrite;
                log.debug("write to socketid " + socket.getSocketId() +" len " + bytewrite );
                if(!writebuffer.hasRemaining()) {
                    itr.remove();
                } else {
                    log.debug("data is not written yet");
                    break;
                }
            }
            if(!readyBuffers.isEmpty()) {
                socket.addInterestedOps(SelectionKey.OP_WRITE);
                socket.getKey().selector().wakeup();
            }
            listener.onComplete(totWrite, null);
        } catch (IOException e) {
            listener.onException(e);
        } finally {
            socket.unLockWrite();
        }
    }

    public int write(SocketChannel socketChannel, ByteBuffer byteBuffer) throws IOException{
        int bytesWritten = socketChannel.write(byteBuffer);
        int totalBytesWritten = bytesWritten;
        while(bytesWritten > 0 && byteBuffer.hasRemaining()) {
            bytesWritten = socketChannel.write(byteBuffer);
            totalBytesWritten += bytesWritten;
        }
        return totalBytesWritten;
    }

}
