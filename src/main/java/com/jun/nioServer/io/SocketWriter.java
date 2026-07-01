package com.jun.nioServer.io;

import com.jun.nioServer.Connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

/**
 * Drains the connection's pending write buffers on a worker thread. If a buffer is only
 * partially written (kernel buffer full), re-arms OP_WRITE so the reactor will retry.
 */
public class SocketWriter implements Runnable {

    private final Connection connection;
    private final IoCallback callback;

    public SocketWriter(Connection connection, IoCallback callback) {
        this.connection = connection;
        this.callback = callback;
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
                int bytesWritten = drain(connection.getSocketChannel(), buffer);
                totalWritten += bytesWritten;
                if (!buffer.hasRemaining()) {
                    itr.remove();
                } else {
                    break;
                }
            }
            if (!readyBuffers.isEmpty()) {
                // Kernel send buffer filled: the broken buffer (partially written) plus any
                // that follow are still pending. Return them to the front of the write queue
                // so the next cycle resumes in order — otherwise the remainder is lost and a
                // large response arrives truncated.
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

    private int drain(SocketChannel socketChannel, ByteBuffer buffer) throws IOException {
        int written = socketChannel.write(buffer);
        int totalBytesWritten = written;
        while (written > 0 && buffer.hasRemaining()) {
            written = socketChannel.write(buffer);
            totalBytesWritten += written;
        }
        return totalBytesWritten;
    }
}
