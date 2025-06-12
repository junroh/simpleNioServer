package com.jun.config;

import java.io.InputStream;
import java.util.Properties;
import org.apache.log4j.Logger; // Assuming Log4j is used, as seen in Server.java

public class ServerConfig {
    private static final Logger log = Logger.getLogger(ServerConfig.class);

    /** Port for the threaded server. */
    public static int THREADED_SERVER_PORT;
    /** Socket backlog for the threaded server. */
    public static int THREADED_SERVER_BACKLOG;
    /** Thread pool size for the threaded server. */
    public static int THREADED_SERVER_POOL_SIZE;

    /** Port for the NIO server. */
    public static int NIO_SERVER_PORT;
    /** Whether SSL/TLS is enabled for the NIO server. */
    public static boolean NIO_SERVER_SSL_ENABLED;

    // NIO Acceptor Configuration
    /** Network address for the NIO Acceptor to bind to. */
    public static String NIO_ACCEPTOR_ADDRESS;
    /** Socket backlog for the NIO Acceptor. */
    public static int NIO_ACCEPTOR_BACKLOG;
    /** Number of IOReactor threads for the NIO Acceptor. */
    public static int NIO_ACCEPTOR_NUM_IOREACTOR;
    /** Number of worker threads for reading socket data in IOReactors. */
    public static int NIO_ACCEPTOR_NUM_READER_THREADS;
    /** Number of worker threads for writing socket data in IOReactors. */
    public static int NIO_ACCEPTOR_NUM_WRITER_THREADS;
    /** Whether the Acceptor's main socket channel is in blocking mode (true) or non-blocking mode (false). */
    public static boolean NIO_ACCEPTOR_IS_BLOCKING;

    // SSL Configuration (Common for NIO server if SSL is enabled)
    /** Path to the SSL keystore file (e.g., JKS). */
    public static String SSL_KEYSTORE_PATH;
    /** Password for the SSL keystore. */
    public static String SSL_KEYSTORE_PASSWORD;
    /** Password for the key within the SSL keystore. */
    public static String SSL_KEY_PASSWORD;
    /** Path to the SSL truststore file (e.g., JKS), if client authentication is used. */
    public static String SSL_TRUSTSTORE_PATH;
    /** Password for the SSL truststore. */
    public static String SSL_TRUSTSTORE_PASSWORD;

    // NIO Message Handler Configuration
    /** Start of the HTTP response header, including status line, content type, and content length placeholder. */
    public static String NIO_MSG_HANDLER_STATIC_RESPONSE_PART1;
    /** End of HTTP headers section (double CRLF). */
    public static String NIO_MSG_HANDLER_STATIC_RESPONSE_PART2;
    /** Format string for the HTML response body, taking two integer arguments (socket ID, message ID). */
    public static String NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT;

    // Server Type Configuration
    /** System property key used to specify the server type (e.g., "nio" or "threaded"). */
    public static String SERVER_TYPE_PROPERTY_KEY;
    /** Value for `server.type` system property to select the NIO server. */
    public static String SERVER_TYPE_NIO;
    /** Value for `server.type` system property to select the threaded server. */
    public static String SERVER_TYPE_THREADED;
    /** Default server type to use if the system property is not set or is unrecognized. */
    public static String DEFAULT_SERVER_TYPE;

    static {
        Properties props = new Properties();
        try (InputStream input = ServerConfig.class.getClassLoader().getResourceAsStream("server.properties")) {
            if (input == null) {
                log.error("Sorry, unable to find server.properties, using default configurations.");
                setDefaultProperties();
            } else {
                props.load(input);
                loadProperties(props);
            }
        } catch (Exception e) { // Catch generic Exception to handle various issues like IOException or NullPointerException if getResourceAsStream returns null and it's not handled before load.
            log.error("Error loading server.properties, using default configurations.", e);
            setDefaultProperties();
        }
    }

    private static void loadProperties(Properties props) {
        THREADED_SERVER_PORT = getIntProperty(props, "threaded.server.port", 8080);
        THREADED_SERVER_BACKLOG = getIntProperty(props, "threaded.server.backlog", 1024);
        THREADED_SERVER_POOL_SIZE = getIntProperty(props, "threaded.server.pool.size", 100);

        NIO_SERVER_PORT = getIntProperty(props, "nio.server.port", 8080);
        NIO_SERVER_SSL_ENABLED = getBooleanProperty(props, "nio.server.ssl.enabled", false);

        NIO_ACCEPTOR_ADDRESS = props.getProperty("nio.acceptor.address", "localhost");
        NIO_ACCEPTOR_BACKLOG = getIntProperty(props, "nio.acceptor.backlog", 1024);
        NIO_ACCEPTOR_NUM_IOREACTOR = getIntProperty(props, "nio.acceptor.num.ioreactor", 1);
        NIO_ACCEPTOR_NUM_READER_THREADS = getIntProperty(props, "nio.acceptor.num.reader.threads", 2);
        NIO_ACCEPTOR_NUM_WRITER_THREADS = getIntProperty(props, "nio.acceptor.num.writer.threads", 2);
        NIO_ACCEPTOR_IS_BLOCKING = getBooleanProperty(props, "nio.acceptor.is.blocking", true);

        SSL_KEYSTORE_PATH = props.getProperty("ssl.keystore.path", "./src/main/resources/server.jks");
        SSL_KEYSTORE_PASSWORD = props.getProperty("ssl.keystore.password", "storepass");
        SSL_KEY_PASSWORD = props.getProperty("ssl.key.password", "keypass");
        SSL_TRUSTSTORE_PATH = props.getProperty("ssl.truststore.path", "./src/main/resources/trustedCerts.jks");
        SSL_TRUSTSTORE_PASSWORD = props.getProperty("ssl.truststore.password", "storepass");

        NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 = props.getProperty("nio.msg.handler.static.response.part1", "HTTP/1.1 200 OK\\r\\nContent-Type: text/html\\r\\nContent-Length: ");
        NIO_MSG_HANDLER_STATIC_RESPONSE_PART2 = props.getProperty("nio.msg.handler.static.response.part2", "\\r\\n\\r\\n");
        NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT = props.getProperty("nio.msg.handler.response.body.format", "<html><body>Hello World(%d-%d)</body></html>");

        SERVER_TYPE_PROPERTY_KEY = props.getProperty("server.type.property.key", "server.type");
        SERVER_TYPE_NIO = props.getProperty("server.type.nio", "nio");
        SERVER_TYPE_THREADED = props.getProperty("server.type.threaded", "threaded");
        DEFAULT_SERVER_TYPE = props.getProperty("default.server.type", "nio");
    }

    private static void setDefaultProperties() {
        THREADED_SERVER_PORT = 8080;
        THREADED_SERVER_BACKLOG = 1024;
        THREADED_SERVER_POOL_SIZE = 100;

        NIO_SERVER_PORT = 8080;
        NIO_SERVER_SSL_ENABLED = false;

        NIO_ACCEPTOR_ADDRESS = "localhost";
        NIO_ACCEPTOR_BACKLOG = 1024;
        NIO_ACCEPTOR_NUM_IOREACTOR = 1;
        NIO_ACCEPTOR_NUM_READER_THREADS = 2;
        NIO_ACCEPTOR_NUM_WRITER_THREADS = 2;
        NIO_ACCEPTOR_IS_BLOCKING = true;

        SSL_KEYSTORE_PATH = "./src/main/resources/server.jks";
        SSL_KEYSTORE_PASSWORD = "storepass";
        SSL_KEY_PASSWORD = "keypass";
        SSL_TRUSTSTORE_PATH = "./src/main/resources/trustedCerts.jks";
        SSL_TRUSTSTORE_PASSWORD = "storepass";

        NIO_MSG_HANDLER_STATIC_RESPONSE_PART1 = "HTTP/1.1 200 OK\\r\\nContent-Type: text/html\\r\\nContent-Length: ";
        NIO_MSG_HANDLER_STATIC_RESPONSE_PART2 = "\\r\\n\\r\\n";
        NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT = "<html><body>Hello World(%d-%d)</body></html>";

        SERVER_TYPE_PROPERTY_KEY = "server.type";
        SERVER_TYPE_NIO = "nio";
        SERVER_TYPE_THREADED = "threaded";
        DEFAULT_SERVER_TYPE = SERVER_TYPE_NIO; // Default to NIO
    }

    private static int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid format for integer property '" + key + "', using default value: " + defaultValue, e);
            }
        }
        return defaultValue;
    }

    private static boolean getBooleanProperty(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}
