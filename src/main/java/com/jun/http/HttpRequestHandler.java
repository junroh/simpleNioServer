package com.jun.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

public interface HttpRequestHandler {
    void handle(InputStream inputStream, OutputStream outputStream, String serverText) throws IOException;
}
