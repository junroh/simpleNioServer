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
        // This will be addressed in a later step if general HTTP compliance is tightened.
        String response = "HTTP/1.1 200 OK\n\nWorkerRunnable: " +
                          serverText + " - " +
                          time;
        outputStream.write(response.getBytes("UTF-8")); // Specify charset
        // inputStream.close(); // Original Worker closed input stream here.
        // outputStream.close(); // Original Worker closed output stream here.
        log.debug("Response sent by SimpleThreadedHttpRequestHandler: " + time + " for serverText: " + serverText);
        // The original Worker.java reads from the input stream and closes it.
        // The provided code for SimpleThreadedHttpRequestHandler does not read from input stream.
        // To maintain original behavior, the input stream should be read (even if data is discarded)
        // and then closed if the handler is responsible for stream lifecycle.
        // For now, let's assume the Worker will handle stream closing.
        // Reading from input stream:
        byte[] buffer = new byte[1024];
        while (inputStream.read(buffer) != -1) {
            // Discard data, just consuming to match original behavior of reading then closing.
        }
    }
}
