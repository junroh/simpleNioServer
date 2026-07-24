package com.jun.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Stateful HTTP/1.1 request parser. Reassembles requests split across multiple {@link #feed}
 * calls and emits each completed request to the registered {@link Listener}. Used by both
 * the NIO and threaded transports.
 */
public final class HttpRequestParser {

    private static final int MAX_REQUEST_SIZE = 8 * 1024 * 1024;
    private static final byte[] EMPTY_BODY = new byte[0];

    public interface Listener {
        void onRequest(HttpRequest request);
        void onError(String message);
    }

    private enum State {
        NEED_REQUEST_LINE,
        NEED_HEADERS,
        NEED_BODY_FIXED,
        NEED_CHUNK_SIZE,
        NEED_CHUNK_DATA,
        NEED_CHUNK_TRAILER
    }

    private final Listener listener;

    private byte[] buf = new byte[4096];
    private int len;
    private int pos;

    private State state = State.NEED_REQUEST_LINE;
    private String currentMethod;
    private String currentUri;
    private String currentVersion;
    private HttpHeaders currentHeaders;
    private ByteArrayOutputStream currentBody;
    private long bodyBytesRemaining;
    private long currentChunkBytesRemaining;
    private boolean errored;

    public HttpRequestParser(Listener listener) {
        this.listener = listener;
    }

    public void feed(byte[] data, int offset, int length) {
        if (errored) {
            return;
        }
        ensureCapacity(len + length);
        System.arraycopy(data, offset, buf, len, length);
        len += length;

        boolean progress = true;
        while (progress && !errored) {
            progress = step();
        }

        compact();

        if (len > MAX_REQUEST_SIZE) {
            fail("Request too large (" + len + " bytes)");
        }
    }

    private boolean step() {
        return switch (state) {
            case NEED_REQUEST_LINE -> tryParseRequestLine();
            case NEED_HEADERS -> tryParseHeaderLine();
            case NEED_BODY_FIXED -> tryConsumeFixedBody();
            case NEED_CHUNK_SIZE -> tryParseChunkSize();
            case NEED_CHUNK_DATA -> tryConsumeChunkData();
            case NEED_CHUNK_TRAILER -> tryConsumeTrailer();
            default -> throw new IllegalStateException("Unhandled state " + state);
        };
    }

    private boolean tryParseRequestLine() {
        int eol = findCrlf(pos, len);
        if (eol < 0) {
            return false;
        }

        String requestLine = new String(buf, pos, eol - pos, StandardCharsets.ISO_8859_1);
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            fail("Malformed request line: " + requestLine);
            return false;
        }

        currentMethod = parts[0];
        currentUri = parts[1];
        currentVersion = parts[2];
        currentHeaders = new HttpHeaders();
        if ("HTTP/1.0".equals(currentVersion)) {
            currentHeaders.keepAlive = false;
        }
        // currentBody is created lazily (bodyOut) only when a body is actually present, so
        // the common bodyless request (GET /hello) allocates no ByteArrayOutputStream.
        currentBody = null;
        pos = eol + 2;
        state = State.NEED_HEADERS;
        return true;
    }

    private boolean tryParseHeaderLine() {
        int eol = findCrlf(pos, len);
        if (eol < 0) {
            return false;
        }

        if (eol == pos) {
            pos = eol + 2;
            return finalizeHeaders();
        }

        String headerLine = new String(buf, pos, eol - pos, StandardCharsets.ISO_8859_1);
        int colon = headerLine.indexOf(':');
        if (colon < 0) {
            fail("Malformed header line: " + headerLine);
            return false;
        }
        String name = headerLine.substring(0, colon).trim();
        String value = headerLine.substring(colon + 1).trim();
        currentHeaders.put(name, value);
        applyWellKnownHeader(name, value);

        pos = eol + 2;
        return true;
    }

    private void applyWellKnownHeader(String name, String value) {
        if (HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
            long parsed;
            try {
                parsed = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                fail("Invalid Content-Length: " + value);
                return;
            }
            if (parsed < 0) {
                fail("Negative Content-Length: " + value);
                return;
            }
            currentHeaders.contentLength = parsed;
        } else if (HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
            // Per RFC 7230 §3.3.1, the last coding determines the framing.
            String last = value;
            int comma = value.lastIndexOf(',');
            if (comma >= 0) {
                last = value.substring(comma + 1).trim();
            }
            if (HttpHeaders.VALUE_CHUNKED.equalsIgnoreCase(last)) {
                currentHeaders.chunkedTransferEncoding = true;
            }
        } else if (HttpHeaders.CONNECTION.equalsIgnoreCase(name)) {
            if (HttpHeaders.VALUE_CLOSE.equalsIgnoreCase(value)) {
                currentHeaders.keepAlive = false;
            } else if (HttpHeaders.VALUE_KEEP_ALIVE.equalsIgnoreCase(value)) {
                currentHeaders.keepAlive = true;
            }
        }
    }

    private boolean finalizeHeaders() {
        if (currentHeaders.chunkedTransferEncoding) {
            state = State.NEED_CHUNK_SIZE;
        } else if (currentHeaders.contentLength > MAX_REQUEST_SIZE) {
            fail("Request body too large (" + currentHeaders.contentLength + " bytes)");
        } else if (currentHeaders.contentLength > 0) {
            bodyBytesRemaining = currentHeaders.contentLength;
            state = State.NEED_BODY_FIXED;
        } else {
            emitRequest();
        }
        return true;
    }

    private boolean tryConsumeFixedBody() {
        int available = len - pos;
        if (available <= 0) {
            return false;
        }
        int take = (int) Math.min(available, bodyBytesRemaining);
        bodyOut().write(buf, pos, take);
        pos += take;
        bodyBytesRemaining -= take;
        if (bodyBytesRemaining == 0) {
            emitRequest();
        }
        return take > 0;
    }

    private boolean tryParseChunkSize() {
        int eol = findCrlf(pos, len);
        if (eol < 0) {
            return false;
        }

        String sizeLine = new String(buf, pos, eol - pos, StandardCharsets.ISO_8859_1);
        int semi = sizeLine.indexOf(';');
        String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
        long chunkSize;
        try {
            chunkSize = Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            fail("Malformed chunk size: " + sizeLine);
            return false;
        }
        pos = eol + 2;

        if (chunkSize == 0) {
            state = State.NEED_CHUNK_TRAILER;
        } else {
            currentChunkBytesRemaining = chunkSize;
            state = State.NEED_CHUNK_DATA;
        }
        return true;
    }

    private boolean tryConsumeChunkData() {
        int available = len - pos;
        if (available <= 0) {
            return false;
        }
        if (currentChunkBytesRemaining > 0) {
            int take = (int) Math.min(available, currentChunkBytesRemaining);
            bodyOut().write(buf, pos, take);
            pos += take;
            currentChunkBytesRemaining -= take;
            if (currentBody.size() > MAX_REQUEST_SIZE) {
                fail("Chunked request body too large (" + currentBody.size() + " bytes)");
                return false;
            }
            if (currentChunkBytesRemaining > 0) {
                return true;
            }
        }
        if (len - pos < 2) {
            return false;
        }
        if (buf[pos] != '\r' || buf[pos + 1] != '\n') {
            fail("Missing CRLF after chunk data");
            return false;
        }
        pos += 2;
        state = State.NEED_CHUNK_SIZE;
        return true;
    }

    private boolean tryConsumeTrailer() {
        int eol = findCrlf(pos, len);
        if (eol < 0) {
            return false;
        }
        if (eol == pos) {
            pos = eol + 2;
            emitRequest();
            return true;
        }
        pos = eol + 2;
        return true;
    }

    private void emitRequest() {
        HttpRequest req = new HttpRequest(
                currentMethod, currentUri, currentVersion,
                currentHeaders, currentBody != null ? currentBody.toByteArray() : EMPTY_BODY);
        listener.onRequest(req);

        currentMethod = null;
        currentUri = null;
        currentVersion = null;
        currentHeaders = null;
        currentBody = null;
        bodyBytesRemaining = 0;
        currentChunkBytesRemaining = 0;
        state = State.NEED_REQUEST_LINE;
    }

    private void fail(String message) {
        if (!errored) {
            errored = true;
            listener.onError(message);
        }
    }

    /** Lazily create the body accumulator on first body byte — bodyless requests never do. */
    private ByteArrayOutputStream bodyOut() {
        if (currentBody == null) {
            currentBody = new ByteArrayOutputStream();
        }
        return currentBody;
    }

    private int findCrlf(int start, int end) {
        for (int i = start; i + 1 < end; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void compact() {
        if (pos == 0) {
            return;
        }
        if (pos == len) {
            len = 0;
            pos = 0;
            return;
        }
        int leftover = len - pos;
        System.arraycopy(buf, pos, buf, 0, leftover);
        len = leftover;
        pos = 0;
    }

    private void ensureCapacity(int needed) {
        if (needed <= buf.length) {
            return;
        }
        int newCap = buf.length;
        while (newCap < needed) {
            newCap *= 2;
        }
        byte[] bigger = new byte[newCap];
        System.arraycopy(buf, 0, bigger, 0, len);
        buf = bigger;
    }
}
