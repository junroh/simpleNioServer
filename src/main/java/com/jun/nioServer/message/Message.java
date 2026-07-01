package com.jun.nioServer.message;

import com.jun.nioServer.Connection;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Message envelope owned by a {@link Connection}. Carries:
 * <ul>
 *   <li>{@code chunks}: list of byte arrays — on the request side, the raw bytes that
 *       made up the message; on the response side, the bytes ready to be sent.</li>
 *   <li>{@code payload}: opaque parsed object (e.g. {@code HttpRequest}). Use
 *       {@link #getPayloadAs(Class)} for type-safe access.</li>
 * </ul>
 */
public class Message {

    /** JVM-wide unique id for correlating logs. Not connection-scoped. */
    private static final AtomicInteger nextId = new AtomicInteger();

    private final Connection connection;
    private final int id;
    private final List<byte[]> chunks = new LinkedList<>();
    private Object payload;

    public Message(Connection connection) {
        this.connection = connection;
        this.id = nextId.incrementAndGet();
    }

    public Message(Connection connection, int orgId) {
        this.connection = connection;
        this.id = orgId;
    }

    public Connection getConnection() {
        return connection;
    }

    public int getId() {
        return id;
    }

    public void writeToMessage(byte[] src) {
        writeToMessage(src, 0, src.length);
    }

    public void writeToMessage(byte[] src, int offset, int length) {
        byte[] dst = new byte[length];
        System.arraycopy(src, offset, dst, 0, length);
        chunks.add(dst);
    }

    public List<byte[]> getChunks() {
        return chunks;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public Object getPayload() {
        return payload;
    }

    /**
     * Type-safe payload accessor. Throws {@link IllegalStateException} if the payload is
     * absent or of a different type — callers don't need defensive {@code instanceof} checks.
     */
    public <T> T getPayloadAs(Class<T> type) {
        if (!type.isInstance(payload)) {
            String actual;
            if (payload == null) {
                actual = "null";
            } else {
                actual = payload.getClass().getName();
            }
            throw new IllegalStateException(
                    "Expected payload of type " + type.getName() + " but got " + actual);
        }
        return type.cast(payload);
    }
}
