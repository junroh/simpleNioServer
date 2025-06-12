package com.jun.config;

public class ServerConfig {
    /** Port for the threaded server. */
    public static final int THREADED_SERVER_PORT = 8080;
    /** Socket backlog for the threaded server. */
    public static final int THREADED_SERVER_BACKLOG = 1024;
    /** Thread pool size for the threaded server. */
    public static final int THREADED_SERVER_POOL_SIZE = 100;

    /** Port for the NIO server. */
    public static final int NIO_SERVER_PORT = 8080;
    /** Whether SSL/TLS is enabled for the NIO server. */
    public static final boolean NIO_SERVER_SSL_ENABLED = false;

    // NIO Acceptor Configuration
    /** Network address for the NIO Acceptor to bind to. */
    public static final String NIO_ACCEPTOR_ADDRESS = "localhost";
    /** Socket backlog for the NIO Acceptor. */
    public static final int NIO_ACCEPTOR_BACKLOG = 1024;
    /** Number of IOReactor threads for the NIO Acceptor. */
    public static final int NIO_ACCEPTOR_NUM_IOREACTOR = 1;
    /** Number of worker threads for reading socket data in IOReactors. */
    public static final int NIO_ACCEPTOR_NUM_READER_THREADS = 2;
    /** Number of worker threads for writing socket data in IOReactors. */
    public static final int NIO_ACCEPTOR_NUM_WRITER_THREADS = 2;
    /** Whether the Acceptor's main socket channel is in blocking mode (true) or non-blocking mode (false). */
    public static final boolean NIO_ACCEPTOR_IS_BLOCKING = true;

    // SSL Configuration (Common for NIO server if SSL is enabled)
    /** Path to the SSL keystore file (e.g., JKS). */
    public static final String SSL_KEYSTORE_PATH = "./src/main/resources/server.jks";
    /** Password for the SSL keystore. */
    public static final String SSL_KEYSTORE_PASSWORD = "storepass";
    /** Password for the key within the SSL keystore. */
    public static final String SSL_KEY_PASSWORD = "keypass";
    /** Path to the SSL truststore file (e.g., JKS), if client authentication is used. */
    public static final String SSL_TRUSTSTORE_PATH = "./src/main/resources/trustedCerts.jks";
    /** Password for the SSL truststore. */
    public static final String SSL_TRUSTSTORE_PASSWORD = "storepass";

    // NIO Message Handler Configuration
    /** Start of the HTTP response header, including status line, content type, and content length placeholder. */
    public static final String NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 = "HTTP/1.1 200 OK\\r\\nContent-Type: text/html\\r\\nContent-Length: ";
    /** End of HTTP headers section (double CRLF). */
    public static final String NIO_MSG_HANDLER_STATIC_RESPONSE_PART2 = "\\r\\n\\r\\n";
    /** Format string for the HTML response body, taking two integer arguments (socket ID, message ID). */
    public static final String NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT = "<html><body>Hello World(%d-%d)</body></html>";

    // Server Type Configuration
    /** System property key used to specify the server type (e.g., "nio" or "threaded"). */
    public static final String SERVER_TYPE_PROPERTY_KEY = "server.type";
    /** Value for `server.type` system property to select the NIO server. */
    public static final String SERVER_TYPE_NIO = "nio";
    /** Value for `server.type` system property to select the threaded server. */
    public static final String SERVER_TYPE_THREADED = "threaded";
    /** Default server type to use if the system property is not set or is unrecognized. */
    public static final String DEFAULT_SERVER_TYPE = SERVER_TYPE_NIO;
}
