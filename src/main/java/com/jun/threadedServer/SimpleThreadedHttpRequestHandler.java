package com.jun.threadedServer;

import com.jun.http.HttpRequestHandler;
import org.apache.log4j.Logger;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

public class SimpleThreadedHttpRequestHandler implements HttpRequestHandler {
    private static final Logger log = Logger.getLogger(SimpleThreadedHttpRequestHandler.class);

    @Override
    public void handle(InputStream inputStream, OutputStream outputStream, String serverText) throws IOException {
        long time = System.currentTimeMillis();
        // Note: Original Worker had "HTTP/1.1 200 OK\n\n..." (double backslash for Java string literal)
        // which means a single backslash n in the actual output.
        // For direct HTTP, it should be \r\n for newlines.
        // However, to match original behavior first, I will use \n.
        // TODO: Address HTTP compliance for line endings (use \r\n instead of \n).
        String response = "HTTP/1.1 200 OK\n\nWorkerRunnable: " +
                          serverText + " - " +
                          time;
        outputStream.write(response.getBytes("UTF-8"));
        log.debug("Response sent by SimpleThreadedHttpRequestHandler: " + time + " for serverText: " + serverText);
        // The Worker.java is expected to manage the socket and stream lifecycle (closing them).
        // This handler consumes the input stream data to mimic original behavior before the Worker closes the socket.
        // TODO: Clarify and confirm stream lifecycle management responsibilities between handler and Worker.
        // Reading from input stream:
        byte[] buffer = new byte[1024];
        while (inputStream.read(buffer) != -1) {
            // Discard data, just consuming to match original behavior of reading then closing.
        }
    }
}
