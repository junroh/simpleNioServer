package com.jun.nioServer.msg;

import com.jun.nioServer.ConnectedSocket;
import org.apache.log4j.Logger;

import java.nio.channels.SelectionKey;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Message {

    private static final Logger log = Logger.getLogger(Message.class);
    private static final AtomicInteger staticId = new AtomicInteger();

    private final ConnectedSocket socketChannel;
    private SelectionKey key;

    private final List<byte[]> datas;
    private Object header;
    private int lastidx;
    private int lastofst;
    private final int id;

    public Message(ConnectedSocket socketChannel) {
        this.socketChannel = socketChannel;
        datas = new LinkedList<>();
        id = staticId.incrementAndGet();
    }

    public Message(ConnectedSocket socketChannel, int orgId) {
        this.socketChannel = socketChannel;
        datas = new LinkedList<>();
        id = orgId;
    }

    public ConnectedSocket getSocketChannel() { return socketChannel; }

    public int getId() {
        return id;
    }

    public void setKey(SelectionKey k) {
        key = k;
    }

    public SelectionKey getKey() {
        return key;
    }

    public void writeToMessage(byte[] src) {
        writeToMessage(src, 0, src.length);
    }

    public void writeToMessage(byte[] src, int offset, int length) {
        byte[] dst = new byte[length];
        System.arraycopy(src, offset, dst, 0, length);
        log.debug(String.format("write msg %d-len:%d on socketid %d(%d)%n[Contents]%n%s",
            datas.size(), length, socketChannel.getSocketId(), id, new String(dst)));
        datas.add(dst);
    }

    public List<byte[]> getDatas() {
        return getDatas(lastidx);
    }

    public List<byte[]> getDatas(int idx) {
        return datas.subList(idx, datas.size());
    }

    public void setLastPos(int idx, int offset) {
        lastidx = idx;
        lastofst = offset;
    }

    public int getLastOffset() {
        return lastofst;
    }

    public void setHeader(Object header) {
        this.header = header;
    }

    public Object getHeader() {
        return header;
    }
}