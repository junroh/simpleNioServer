package com.jun.nioServer.io;

import com.jun.nioServer.Connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

/**
 * Drains the connection's pending write buffers on a worker thread, up to {@code maxBytesPerWrite}
 * bytes per turn. If a buffer is only partially written (kernel buffer full) or the per-turn cap
 * is hit, the remaining buffers are returned to the queue and OP_WRITE is re-armed so the reactor
 * retries — so a large response cannot monopolise the worker (or, in an inline transport, the
 * reactor thread). Mirrors {@code SocketReader}'s {@code maxBytesPerRead} fairness cap.
 */
public class SocketWriter implements Runnable {

    private final Connection connection;
    private final IoCallback callback;
    private final int maxBytesPerWrite;

    public SocketWriter(Connection connection, IoCallback callback, int maxBytesPerWrite) {
        if (maxBytesPerWrite <= 0) {
            throw new IllegalArgumentException("maxBytesPerWrite must be positive");
        }
        this.connection = connection;
        this.callback = callback;
        this.maxBytesPerWrite = maxBytesPerWrite;
    }

    @Override
    public void run() {
        // tryWriteLock ensures only one worker thread writes to this connection at a time.
        if (!connection.tryWriteLock()) {
            // Another writer holds the lock. OP_WRITE was already cleared by the reactor
            // before submitting us, so unless we re-arm it the close-after-writes signal
            // (or any newly-queued response bytes) would never trigger another cycle.
            connection.requestWrite();
            return;
        }
        try {
            List<ByteBuffer> readyBuffers = connection.drainWriteBuffers();
            Iterator<ByteBuffer> itr = readyBuffers.iterator();
            int totalWritten = 0;
            while (itr.hasNext()) {
                ByteBuffer buffer = itr.next();
                totalWritten += drain(connection.getSocketChannel(), buffer,
                        maxBytesPerWrite - totalWritten);
                if (buffer.hasRemaining()) {
                    // Kernel send buffer filled OR the per-turn cap capped this buffer: stop.
                    break;
                }
                itr.remove();
                if (totalWritten >= maxBytesPerWrite) {
                    // Per-turn budget spent; leave the rest queued and yield to other work.
                    break;
                }
            }
            if (!readyBuffers.isEmpty()) {
                // Partially written / capped: return the broken buffer (and any that follow) to
                // the front of the write queue so the next cycle resumes in order — otherwise the
                // remainder is lost and a large response arrives truncated.
                connection.returnUnwrittenBuffers(readyBuffers);
                connection.requestWrite();
            }
            callback.onComplete(totalWritten, null);
        } catch (IOException e) {
            callback.onException(e);
        } finally {
            connection.unlockWrite();
            // Single graceful-close decision, AFTER unlock so writeBuffers.isEmpty() reflects
            // reality and the writeLock is free for maybeClose's own guard.
            connection.maybeClose();
        }
    }

    /** Write from {@code buffer} until it drains, the kernel send buffer fills, or {@code budget}
     *  bytes have been written this turn — whichever comes first. Caps via a temporary limit so
     *  the buffer's leftover stays visible for the next turn. */
    private int drain(SocketChannel socketChannel, ByteBuffer buffer, int budget) throws IOException {
        int originalLimit = buffer.limit();
        if (buffer.remaining() > budget) {
            buffer.limit(buffer.position() + budget);
        }
        try {
            int written = socketChannel.write(buffer);
            int totalBytesWritten = written;
            while (written > 0 && buffer.hasRemaining()) {
                written = socketChannel.write(buffer);
                totalBytesWritten += written;
            }
            return totalBytesWritten;
        } finally {
            buffer.limit(originalLimit);
        }
    }
}
