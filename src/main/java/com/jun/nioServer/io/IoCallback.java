package com.jun.nioServer.io;

import java.nio.ByteBuffer;
import java.util.List;

/** Async I/O completion callback. Either {@link #onComplete} fires or {@link #onException} fires. */
public interface IoCallback {
    void onComplete(int bytesProcessed, List<ByteBuffer> chunks);

    void onException(Exception e);
}
