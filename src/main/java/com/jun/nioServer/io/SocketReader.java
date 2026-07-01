package com.jun.nioServer.io;

import com.jun.nioServer.Connection;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.List;

/**
 * Reads from one connection on a worker thread, then re-arms OP_READ.
 *
 * <p>Each dispatch is capped by {@code maxBytesPerRead}: when the cap is hit, the handler
 * stops and re-arms OP_READ so the reactor can give other sockets a turn on the next cycle.
 */
public class SocketReader implements Runnable {

    private static final Logger log = Logger.getLogger(SocketReader.class);
    private static final int READ_CHUNK_SIZE = 4 * 1024;

    private final Connection connection;
    private final IoCallback callback;
    private final int maxBytesPerRead;
    private boolean eof;

    public SocketReader(Connection connection, IoCallback callback, int maxBytesPerRead) {
        this.connection = connection;
        this.callback = callback;
        this.maxBytesPerRead = maxBytesPerRead;
    }

    @Override
    public void run() {
        // tryReadLock ensures only one worker thread reads from this connection at a time.
        if (!connection.tryReadLock()) {
            // Another reader holds the lock. onRead already cleared OP_READ before submitting
            // us, so we must re-arm it (like SocketWriter's bounce) or the bytes that triggered
            // this event are stranded and the peer hangs — the lock holder won't re-arm for us.
            connection.requestRead();
            return;
        }
        // Re-arm OP_READ unless the peer is done (EOF). Decided before onComplete and applied
        // in finally so it cannot be skipped by an exception on the deliver path.
        boolean rearm = false;
        try {
            int totalBytes = 0;
            int bytesRead;
            List<ByteBuffer> chunks = new LinkedList<>();
            do {
                ByteBuffer chunk = ByteBuffer.allocate(READ_CHUNK_SIZE);
                bytesRead = readChunk(connection, chunk);
                if (bytesRead > 0) {
                    chunk.flip();
                    chunks.add(chunk);
                    totalBytes += bytesRead;
                }
                if (totalBytes >= maxBytesPerRead) {
                    log.debug("read budget exhausted on socketid " + connection.getSocketId()
                            + " (read " + totalBytes + " bytes)");
                    break;
                }
            } while (bytesRead > 0);
            if (!connection.isClosed()) {
                if (eof) {
                    // Peer half-closed (FIN). Mark BEFORE onComplete so the reactor enqueues
                    // a dispatcher pass even when this read carried no bytes — the dispatcher's
                    // leaveInFlight() then closes (via maybeClose) once writes drain. Do NOT
                    // hard-close here: an in-flight response is produced after we return, and
                    // closing now (closeOutbound) would drop it. Stop reading (peer is done).
                    connection.markCloseAfterWrites();
                } else {
                    rearm = true;
                }
                // Deliver whatever was read — even if EOF coalesced with it, the request must
                // be processed. onComplete enqueues the dispatcher.
                callback.onComplete(totalBytes, chunks);
            }
        } catch (IOException e) {
            rearm = false;   // onException closes the connection
            callback.onException(e);
        } finally {
            connection.unlockRead();
            if (rearm && !connection.isClosed()) {
                connection.requestRead();
            }
        }
    }

    private int readChunk(Connection connection, ByteBuffer chunk) throws IOException {
        int totalBytesRead = 0;
        int bytesRead;
        SocketChannel socketChannel = connection.getSocketChannel();
        do {
            bytesRead = socketChannel.read(chunk);
            if (bytesRead <= 0) {
                break;
            }
            totalBytesRead += bytesRead;
        } while (chunk.hasRemaining());
        if (bytesRead == -1) {
            // Peer closed its send side. Record it; run() handles graceful teardown after
            // any bytes read this turn are delivered. Do NOT close here (would kill an
            // in-flight response).
            eof = true;
        }
        return totalBytesRead;
    }
}
