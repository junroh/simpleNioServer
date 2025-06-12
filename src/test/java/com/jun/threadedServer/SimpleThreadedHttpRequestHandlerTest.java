package com.jun.threadedServer;

import com.jun.http.HttpRequestHandler;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
// import java.nio.charset.StandardCharsets; // Not used

import static org.junit.Assert.*;

public class SimpleThreadedHttpRequestHandlerTest {

    @Test
    public void testHandle() throws IOException {
        HttpRequestHandler handler = new SimpleThreadedHttpRequestHandler();

        String serverText = "TestServer";
        String dummyInputData = "Some client request data";
        // For Java 6, use getBytes("UTF-8")
        InputStream mockInputStream = new ByteArrayInputStream(dummyInputData.getBytes("UTF-8"));
        ByteArrayOutputStream mockOutputStream = new ByteArrayOutputStream();

        handler.handle(mockInputStream, mockOutputStream, serverText);

        // For Java 6, use new String(mockOutputStream.toByteArray(), "UTF-8")
        String responseString = new String(mockOutputStream.toByteArray(), "UTF-8");

        assertTrue("Response should start with HTTP status line", responseString.startsWith("HTTP/1.1 200 OK\n\nWorkerRunnable: "));
        assertTrue("Response should contain serverText", responseString.contains("WorkerRunnable: " + serverText + " - "));

        // Check for a timestamp (number) at the end of the relevant part
        String[] parts = responseString.split(" - ");
        assertTrue("Response should have a part after ' - '", parts.length > 1);
        String timePart = parts[parts.length - 1].trim(); // Get the last part, which should be the time
        try {
            Long.parseLong(timePart); // Check if it's a number
        } catch (NumberFormatException e) {
            fail("The part after ' - ' should be a number (timestamp): " + timePart);
        }

        assertEquals("Input stream should be fully consumed", 0, mockInputStream.available());
    }
}
