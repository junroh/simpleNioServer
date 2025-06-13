
package com.jun.nioServer.ssl;

import com.jun.nioServer.ConnectedSocket;
import org.apache.log4j.Logger;

import javax.net.ssl.*;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class SSLEngineBuffer {

    private static final Logger log = Logger.getLogger(SSLEngineBuffer.class);

    private final SSLEngine sslEngine;

    private ByteBuffer netInBuffer;
    private ByteBuffer netOutBuffer;
    private final ByteBuffer appOutBuffer;
    private ByteBuffer appInBuffer;
    private final ConnectedSocket wrappedSocketChannel;
    private final int minAppBufferSize;
    private final int minNetBufferSize;

    public SSLEngineBuffer(SSLContext sslContext, ConnectedSocket wrappedSocketChannel) {
        this.wrappedSocketChannel = wrappedSocketChannel;
        sslEngine = sslContext.createSSLEngine();
        configureSSLEngine();

        SSLSession session = sslEngine.getSession();
        minNetBufferSize = session.getPacketBufferSize();
        minAppBufferSize = session.getApplicationBufferSize();
        session.invalidate();

        netInBuffer = ByteBuffer.allocate(minNetBufferSize);
        netOutBuffer = ByteBuffer.allocate(minNetBufferSize);
        netOutBuffer.flip();

        appInBuffer = ByteBuffer.allocate(minAppBufferSize);
        appOutBuffer = ByteBuffer.allocate(minAppBufferSize);
        appOutBuffer.flip();
    }

    private void configureSSLEngine() {
        sslEngine.setUseClientMode(false);
        sslEngine.setWantClientAuth(false);
        sslEngine.setNeedClientAuth(false);
    }

    private SSLEngineResult.HandshakeStatus doTasks () {
        Runnable runnable;
        while ((runnable = sslEngine.getDelegatedTask())!=null) {
            // TODO: Consider using a cached thread pool for delegated tasks instead of creating a new thread each time.
            new Thread(runnable).start();
        }
        return sslEngine.getHandshakeStatus();
    }

    private SSLEngineResult.HandshakeStatus doUnWrap(SocketChannel socketChannel) throws IOException {
        if (socketChannel.read(netInBuffer) < 0) {
            if(isEngineClosed(sslEngine)) {
                throw new IllegalStateException("SSLEngine was already closed");
            }
            try {
                sslEngine.closeInbound();
            } catch (SSLException e) {
                log.debug("Forced to close inbound " + e.getLocalizedMessage());
            }
            sslEngine.closeOutbound();
            // After closeOutbound the engine will be set to WRAP state, in order to try to send a close message to the client.
            throw new IllegalStateException("Reached to end of stream");
        }

        netInBuffer.flip();
        // any sslException happens in here, handshaking will stop
        SSLEngineResult result = sslEngine.unwrap(netInBuffer, appInBuffer);
        netInBuffer.compact();
        SSLEngineResult.HandshakeStatus handshakeStatus = result.getHandshakeStatus();
        switch (result.getStatus()) {
            case OK:
                break;
            case BUFFER_OVERFLOW:
                // Will occur when peerAppData's capacity is smaller than the data derived from peerNetData's unwrap.
                appInBuffer = enlargeBuffer(appInBuffer, minAppBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                // Will occur either when no data was read from the peer or
                // when the peerNetData buffer was too small to hold all peer's data.
                ByteBuffer replaceBuffer = enlargeBuffer(netInBuffer, minNetBufferSize);
                netInBuffer.flip();
                replaceBuffer.put(netInBuffer);
                netInBuffer = replaceBuffer;
                break;
            case CLOSED:
                if (sslEngine.isOutboundDone()) {
                    throw new IllegalStateException("SSLEngine was already closed");
                } else {
                    sslEngine.closeOutbound();
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
                }
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return handshakeStatus;
    }

    private SSLEngineResult.HandshakeStatus doWrap(SocketChannel socketChannel) throws IOException {
        SSLEngineResult.HandshakeStatus handshakeStatus;
        SSLEngineResult result;

        netOutBuffer.clear();
        // any error happens on wrapping, it will stop handshaking
        result = sslEngine.wrap(appOutBuffer, netOutBuffer);
        handshakeStatus = result.getHandshakeStatus();
        switch (result.getStatus()) {
            case OK:
                netOutBuffer.flip();
                while (netOutBuffer.hasRemaining()) {
                    socketChannel.write(netOutBuffer);
                }
                break;
            case BUFFER_OVERFLOW:
                // Will occur if there is not enough space in myNetData buffer to write all the data that would be generated by the method wrap.
                // Since myNetData is set to session's packet size we should not get to this point because SSLEngine is supposed
                // to produce messages smaller or equal to that, but a general handling would be the following:
                netOutBuffer = enlargeBuffer(netOutBuffer, minNetBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                throw new SSLException("Buffer underflow occurred after a wrap. I don't think we should ever get here.");
            case CLOSED:
                try {
                    netOutBuffer.flip();
                    while (netOutBuffer.hasRemaining()) {
                        socketChannel.write(netOutBuffer);
                    }
                    // At this point the handshake status will probably be NEED_UNWRAP so we make sure that peerNetData is clear to read.
                    netInBuffer.clear();
                } catch (IOException e) {
                    log.error("Failed to send server's CLOSE message due to socket channel's failure.");
                    handshakeStatus = sslEngine.getHandshakeStatus();
                }
                break;
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return handshakeStatus;
    }

    private boolean isEngineClosed(SSLEngine engine) {
        return (engine.isOutboundDone() && engine.isInboundDone());
    }

    private ByteBuffer enlargeBuffer(ByteBuffer buffer, int sessionProposedCapacity) {
        if (sessionProposedCapacity > buffer.capacity()) {
            buffer = ByteBuffer.allocate(sessionProposedCapacity);
        } else {
            buffer = ByteBuffer.allocate(buffer.capacity() * 2);
        }
        return buffer;
    }

    private void doHandshake() throws IOException {
        netOutBuffer.clear();
        netInBuffer.clear();
        SocketChannel socketChannel = wrappedSocketChannel.getSocketChannel();
        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED
            && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            log.trace("handshakeStatus is " + handshakeStatus);
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    handshakeStatus = doUnWrap(socketChannel);
                    break;
                case NEED_WRAP:
                    handshakeStatus = doWrap(socketChannel);
                    break;
                case NEED_TASK:
                    handshakeStatus = doTasks();
                    break;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + handshakeStatus);
            }
        }
    }

    public boolean init() throws IOException {
        log.debug("Ssl handshake started " + wrappedSocketChannel.getSocketId());
        sslEngine.beginHandshake();
        try {
            doHandshake();
        } catch (IOException e) {
            log.error("failed to handshake due to " + e.getLocalizedMessage() + " "
                    + wrappedSocketChannel.getSocketId());
            close();
            return false;
        }
        log.debug("Ssl handshake completed " + wrappedSocketChannel.getSocketId());
        return true;
    }

    public void close() {
        sslEngine.closeOutbound();
        try {
            doHandshake();
        } catch (Exception ignore) {
            // IllegalStateException: SSLEngine was already closed happens in here.
            // no need to handle it. it is expected one.
        }
        log.debug("closed ssl connection on " + wrappedSocketChannel.getSocketId());
    }

    public ByteBuffer unwrap(ByteBuffer netBuffer) throws SSLException {
        ByteBuffer appBuffer = ByteBuffer.allocate(minAppBufferSize);
        netBuffer.rewind();
        SSLEngineResult result = sslEngine.unwrap(netBuffer, appBuffer);
        switch (result.getStatus()) {
            case OK:
                break;
            case BUFFER_OVERFLOW:
                //todo: retry
                log.info("buffer overflow");
                appBuffer = enlargeBuffer(appBuffer, minAppBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                throw new BufferUnderflowException();
            case CLOSED:
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return appBuffer;
    }

    public ByteBuffer wrap(ByteBuffer appBuffer) throws SSLException {
        ByteBuffer netBuffer = ByteBuffer.allocate(minNetBufferSize);
        netBuffer.clear();
        SSLEngineResult result = sslEngine.wrap(appBuffer, netBuffer);
        switch (result.getStatus()) {
            case OK:
                break;
            case BUFFER_OVERFLOW:
                //todo: retry
                netBuffer = enlargeBuffer(netBuffer, minNetBufferSize);
                break;
            case BUFFER_UNDERFLOW:
                throw new BufferUnderflowException();
            case CLOSED:
            default:
                throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
        }
        return netBuffer;
    }
}
