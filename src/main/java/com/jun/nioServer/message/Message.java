package com.jun.nioServer.message;

import com.jun.nioServer.Connection;

import java.util.Collections;
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
    /** Lazily created — request-side messages carry only a {@code payload} and never touch chunks. */
    private List<byte[]> chunks;
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
        addChunk(dst);
    }

    /**
     * Append a chunk <em>without copying</em>; the message takes ownership, so the caller
     * must not mutate {@code chunk} afterwards. Use when the array is freshly produced and
     * not retained elsewhere (e.g. {@link com.jun.http.HttpResponse#toBytes}) — avoids the
     * defensive copy {@link #writeToMessage} makes.
     */
    public void addChunk(byte[] chunk) {
        chunks().add(chunk);
    }

    public List<byte[]> getChunks() {
        return chunks == null ? Collections.emptyList() : chunks;
    }

    private List<byte[]> chunks() {
        if (chunks == null) {
            chunks = new LinkedList<>();
        }
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
