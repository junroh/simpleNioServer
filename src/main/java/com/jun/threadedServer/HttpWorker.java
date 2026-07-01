package com.jun.threadedServer;

import com.jun.http.HttpRequest;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRequestParser;
import com.jun.http.HttpResponse;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-connection worker for the threaded server. Drives the shared
 * {@link HttpRequestParser} from a blocking {@link InputStream}, dispatches each parsed
 * request to {@link HttpRequestHandler}, and writes the response bytes back. Honors
 * HTTP/1.1 keep-alive until the client signals close, EOF arrives, or the parser errors.
 */
public class HttpWorker implements Runnable {

    private static final Logger log = Logger.getLogger(HttpWorker.class);
    private static final int READ_BUF_SIZE = 4 * 1024;

    private final Socket clientSocket;
    private final HttpRequestHandler appHandler;

    public HttpWorker(Socket clientSocket, HttpRequestHandler appHandler) {
        this.clientSocket = clientSocket;
        this.appHandler = appHandler;
    }

    @Override
    public void run() {
        InputStream input = null;
        OutputStream output = null;
        try {
            input = clientSocket.getInputStream();
            output = clientSocket.getOutputStream();
            processStream(input, output, appHandler);
        } catch (IOException e) {
            log.error("Error processing client request for socket: " + clientSocket, e);
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                log.warn("Error closing client socket: " + clientSocket, e);
            }
        }
    }

    /**
     * Drive the read/parse/handle/write loop on arbitrary streams. Returns once the loop
     * exits (EOF, {@code Connection: close}, or parser error). Callers own the streams.
     */
    public static void processStream(InputStream input, OutputStream output,
                                     HttpRequestHandler appHandler) throws IOException {
        List<HttpRequest> ready = new ArrayList<>();
        boolean[] errored = {false};
        HttpRequestParser parser = new HttpRequestParser(new HttpRequestParser.Listener() {
            @Override
            public void onRequest(HttpRequest req) {
                ready.add(req);
            }

            @Override
            public void onError(String message) {
                log.warn("HTTP parse error: " + message);
                errored[0] = true;
            }
        });

        byte[] readBuf = new byte[READ_BUF_SIZE];
        boolean keepAlive = true;
        while (keepAlive && !errored[0]) {
            int read = input.read(readBuf);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            parser.feed(readBuf, 0, read);

            for (HttpRequest req : ready) {
                HttpResponse resp = appHandler.handle(req);
                output.write(resp.toBytes(req.isHead()));
                output.flush();
                if (!req.isKeepAlive()) {
                    // Per RFC 7230 §6.6: don't process further pipelined requests after close.
                    keepAlive = false;
                    break;
                }
            }
            ready.clear();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignore) {
            // best-effort
        }
    }
}
