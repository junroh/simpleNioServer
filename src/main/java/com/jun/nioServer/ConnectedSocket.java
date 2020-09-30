package com.jun.nioServer;

import com.jun.nioServer.msg.Message;
import com.jun.nioServer.ssl.SSLEngineBuffer;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class ConnectedSocket {
    private static final Logger log = Logger.getLogger(ConnectedSocket.class);

    private final int socketId;
    private final SocketChannel socketChannel;
    private SelectionKey key;

    private SSLEngineBuffer sslEngineBuffer;

    private final ReentrantLock readLock;
    private final ReentrantLock writeLock;
    private final AtomicBoolean closed;

    // for read
    private final ConcurrentLinkedDeque<ByteBuffer> readbuffers;
    private final ConcurrentLinkedDeque<Message> readMsgs;
    // for write
    private final ConcurrentLinkedDeque<ByteBuffer> writebuffers;
    private final ConcurrentLinkedDeque<Message> writeMsgs;

    public boolean tryReadLock() {
        return readLock.tryLock();
    }

    public void unLockRead() {
        readLock.unlock();
    }

    public boolean tryWriteLock() {
        return writeLock.tryLock();
    }

    public void unLockWrite() {
        writeLock.unlock();
    }

    ConnectedSocket(int sockId, SocketChannel socket) throws IOException {
        socketId = sockId;
        socketChannel = socket;
        socketChannel.configureBlocking(false);

        readLock = new ReentrantLock();
        writeLock = new ReentrantLock();
        closed = new AtomicBoolean(false);

        readbuffers = new ConcurrentLinkedDeque<>();
        readMsgs = new ConcurrentLinkedDeque<>();
        writebuffers = new ConcurrentLinkedDeque<>();
        writeMsgs = new ConcurrentLinkedDeque<>();
    }

    ConnectedSocket(int sockId, SocketChannel socket, SSLContext sslContext) throws IOException {
        this(sockId, socket);
        if(sslContext!=null) {
            sslEngineBuffer = new SSLEngineBuffer(sslContext, this);
            if(!sslEngineBuffer.init()) {
                throw new IOException("Failed to do SSL handshake");
            }
        }
    }

    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    public int getSocketId() {
        return socketId;
    }

    public SelectionKey getKey() {
        return key;
    }

    public synchronized void configKey(SelectionKey k) {
        if(closed.get()) {
            return;
        }
        key = k;
        key.attach(this);
        key.interestOps(SelectionKey.OP_READ);
    }

    public synchronized void addInterestedOps(int ops) {
        if(closed.get()) {
            return;
        }
        key.interestOps(key.interestOps()|(ops));
        log.debug("(after adding) interest op is " + key.interestOps() + " socketid " + socketId);
    }

    public synchronized void clrInterestedOps(int ops) {
        if(closed.get()) {
            return;
        }
        key.interestOps(key.interestOps()&(~(ops)));
        log.debug("(after clearing) interest op is " + key.interestOps() + " socketid " + socketId);
    }

    public boolean isClosed() {
        log.debug("isClosed? " + closed.get() +" "+ !socketChannel.isConnected() +" "+ !socketChannel.isOpen());
        return closed.get() || !socketChannel.isConnected() || !socketChannel.isOpen();
    }

    public void close() {
        if(!closed.compareAndSet(false,true)) {
            return;
        }
        log.info("socketid " + socketId + " was closed");
        try {
            if(sslEngineBuffer!=null) {
                sslEngineBuffer.close();
            }
            key.attach(null);
            key.cancel();
            socketChannel.close();  //key.channel().close(); (same one)
        } catch (Exception e) {
            log.error("Error on closing socket " + socketId + " due to " + e);
        }
    }

    public void addSocketReadData(ByteBuffer buff) {
        readbuffers.offer(buff);
    }

    public List<ByteBuffer> getSocketReadData() {
        List<ByteBuffer> buffers = new LinkedList<>();
        ByteBuffer buff;
        while((buff=readbuffers.poll())!=null) {
            if(sslEngineBuffer!=null) {
                while(buff.hasRemaining()) {
                    ByteBuffer appBuffer;
                    try {
                        appBuffer = sslEngineBuffer.unwrap(buff);
                    /*} catch (BufferUnderflowException e) {
                        log.debug("buffer underflow");*/
                    } catch (Exception e) {
                        log.error("error on unwrapping data " + e);
                        return new LinkedList<>();
                    }
                    appBuffer.flip();
                    buffers.add(appBuffer);
                }
            } else {
                buffers.add(buff);
            }
        }
        return buffers;
    }

    public void addReadReadyMsg(Message msg) {
        readMsgs.add(msg);
    }

    public List<Message> getReadReadyMessages() {
        List<Message> msgs = new LinkedList<>();
        Message buff;
        while((buff=readMsgs.poll())!=null) {
            msgs.add(buff);
        }
        return msgs;
    }

    public void addWriteReadyMsg(Message msg) {
        writeMsgs.add(msg);
    }

    public boolean makeReadyBuffer() {
        Message msg;
        while((msg=writeMsgs.poll())!=null) {
            for(byte[] buff: msg.getDatas()) {
                ByteBuffer byteBuffer = ByteBuffer.wrap(buff);
                if(sslEngineBuffer!=null) {
                    ByteBuffer netBuffer;
                    try {
                        netBuffer = sslEngineBuffer.wrap(byteBuffer);
                /*} catch (BufferUnderflowException e) {
                    log.debug("buffer underflow");*/
                    } catch (Exception e) {
                        log.error("error on unwrapping data " + e);
                        return false;
                    }
                    netBuffer.flip();
                    writebuffers.add(netBuffer);
                } else {
                    writebuffers.add(byteBuffer);
                }
            }
        }
        return !writebuffers.isEmpty();
    }

    public List<ByteBuffer> getWritebuffers() {
        List<ByteBuffer> buffers = new LinkedList<>();
        ByteBuffer buff;
        while((buff=writebuffers.poll())!=null) {
            buffers.add(buff);
        }
        return buffers;
    }

}

