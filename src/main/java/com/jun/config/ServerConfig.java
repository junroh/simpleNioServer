package com.jun.config;

public class ServerConfig {
    public static final int THREADED_SERVER_PORT = 8080;
    public static final int THREADED_SERVER_BACKLOG = 1024;
    public static final int THREADED_SERVER_POOL_SIZE = 100;

    public static final int NIO_SERVER_PORT = 8080;
    public static final boolean NIO_SERVER_SSL_ENABLED = false;
    public static final String NIO_ACCEPTOR_ADDRESS = "localhost";
    public static final int NIO_ACCEPTOR_BACKLOG = 1024;
    public static final int NIO_ACCEPTOR_NUM_IOREACTOR = 1;
    public static final int NIO_ACCEPTOR_NUM_READER_THREADS = 2;
    public static final int NIO_ACCEPTOR_NUM_WRITER_THREADS = 2;
    public static final boolean NIO_ACCEPTOR_IS_BLOCKING = true;
    public static final String SSL_KEYSTORE_PATH = "./src/main/resources/server.jks";
    public static final String SSL_KEYSTORE_PASSWORD = "storepass";
    public static final String SSL_KEY_PASSWORD = "keypass";
    public static final String SSL_TRUSTSTORE_PATH = "./src/main/resources/trustedCerts.jks";
    public static final String SSL_TRUSTSTORE_PASSWORD = "storepass";
    public static final String NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 = "HTTP/1.1 200 OK\\r\\nContent-Type: text/html\\r\\nContent-Length: ";
    public static final String NIO_MSG_HANDLER_STATIC_RESPONSE_PART2 = "\\r\\n\\r\\n";
    public static final String NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT = "<html><body>Hello World(%d-%d)</body></html>";

    public static final String SERVER_TYPE_PROPERTY_KEY = "server.type";
    public static final String SERVER_TYPE_NIO = "nio";
    public static final String SERVER_TYPE_THREADED = "threaded";
    public static final String DEFAULT_SERVER_TYPE = SERVER_TYPE_NIO;
}
