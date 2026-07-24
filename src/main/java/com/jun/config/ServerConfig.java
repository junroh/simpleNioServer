package com.jun.config;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Immutable server configuration. Build from a {@link Properties} (any missing keys
 * fall back to documented defaults), or use {@link #defaults()} to load
 * {@code server.properties} from the classpath.
 *
 * <p>System properties override file-based values, so the benchmark runner (or any
 * caller) can flip any key with {@code -Dkey=value} without rewriting the file.
 *
 * <p>Schema (see {@code src/main/resources/server.properties} for the canonical list):
 * <pre>
 *   server.address                  — host to bind
 *   server.port                     — TCP port
 *   server.backlog                  — OS accept-queue depth
 *   server.type                     — "nio" | "threaded" | "virtual"
 *
 *   threaded.server.pool.size       — Threaded transport pool size
 *
 *   nio.num.io.reactors             — IOReactor count
 *   nio.num.io.threads              — I/O pool size per reactor
 *   nio.async.dispatch.enabled      — wrap MessageProcessor in AsyncMessageProcessor
 *   nio.async.dispatch.executor     — "virtual" | "fixed:&lt;N&gt;"
 *   nio.registrar.pool.size         — max concurrent connection registrations / SSL handshakes
 *   nio.registrar.queue.capacity    — registrar queue depth before new connections are rejected
 *   nio.ssl.handshake.timeout.ms    — watchdog deadline for a single SSL handshake
 *
 *   ssl.enabled                     — TLS on/off
 *   ssl.keystore.path / password    — JKS for server cert
 *   ssl.key.password                — individual key password
 *   ssl.truststore.path / password  — JKS of trusted CAs (mTLS-ready)
 * </pre>
 */
public final class ServerConfig {

    private static final Logger log = Logger.getLogger(ServerConfig.class);

    /** Sysprop key consulted by {@code Server.main} to choose the transport. */
    public static final String SERVER_TYPE_PROPERTY = "server.type";

    public static final String TYPE_NIO = "nio";
    public static final String TYPE_THREADED = "threaded";
    public static final String TYPE_VIRTUAL = "virtual";

    public static final String DEFAULT_SERVER_ADDRESS               = "localhost";
    public static final int    DEFAULT_SERVER_PORT                  = 8080;
    public static final int    DEFAULT_SERVER_BACKLOG               = 1024;
    public static final int    DEFAULT_THREADED_POOL_SIZE           = 100;
    public static final int    DEFAULT_NIO_NUM_IO_REACTORS          = 1;
    public static final int    DEFAULT_NIO_NUM_IO_THREADS           = 4;
    public static final boolean DEFAULT_NIO_ASYNC_DISPATCH_ENABLED  = false;
    public static final String DEFAULT_NIO_ASYNC_DISPATCH_EXECUTOR  = "fixed:500";
    public static final int    DEFAULT_NIO_REGISTRAR_POOL_SIZE      = 64;
    public static final int    DEFAULT_NIO_REGISTRAR_QUEUE_CAPACITY = 256;
    public static final int    DEFAULT_NIO_SSL_HANDSHAKE_TIMEOUT_MS = 10_000;
    public static final boolean DEFAULT_SSL_ENABLED                  = false;
    public static final String DEFAULT_SSL_KEYSTORE_PATH             = "./src/main/resources/server.jks";
    public static final String DEFAULT_SSL_KEYSTORE_PASSWORD         = "storepass";
    public static final String DEFAULT_SSL_KEY_PASSWORD              = "keypass";
    public static final String DEFAULT_SSL_TRUSTSTORE_PATH           = "./src/main/resources/trustedCerts.jks";
    public static final String DEFAULT_SSL_TRUSTSTORE_PASSWORD       = "storepass";

    private final String serverAddress;
    private final int serverPort;
    private final int serverBacklog;
    private final String serverType;

    private final int threadedServerPoolSize;

    private final int nioNumIoReactors;
    private final int nioNumIoThreads;
    private final boolean nioAsyncDispatchEnabled;
    private final String nioAsyncDispatchExecutor;
    private final int nioRegistrarPoolSize;
    private final int nioRegistrarQueueCapacity;
    private final int nioSslHandshakeTimeoutMs;

    private final boolean sslEnabled;
    private final String sslKeystorePath;
    private final String sslKeystorePassword;
    private final String sslKeyPassword;
    private final String sslTruststorePath;
    private final String sslTruststorePassword;

    public ServerConfig(Properties props) {
        if (props == null) {
            props = new Properties();
        }
        Properties merged = new Properties();
        merged.putAll(props);
        merged.putAll(System.getProperties());
        props = merged;

        this.serverAddress = props.getProperty("server.address", DEFAULT_SERVER_ADDRESS);
        this.serverPort = getIntInRange(props, "server.port", DEFAULT_SERVER_PORT, 1, 65535);
        this.serverBacklog = getPositiveInt(props, "server.backlog", DEFAULT_SERVER_BACKLOG);
        this.serverType = props.getProperty(SERVER_TYPE_PROPERTY, TYPE_NIO);

        this.threadedServerPoolSize = getPositiveInt(props, "threaded.server.pool.size", DEFAULT_THREADED_POOL_SIZE);

        this.nioNumIoReactors = getPositiveInt(props, "nio.num.io.reactors", DEFAULT_NIO_NUM_IO_REACTORS);
        this.nioNumIoThreads = getPositiveInt(props, "nio.num.io.threads", DEFAULT_NIO_NUM_IO_THREADS);
        this.nioAsyncDispatchEnabled = getBoolean(props, "nio.async.dispatch.enabled", DEFAULT_NIO_ASYNC_DISPATCH_ENABLED);
        this.nioAsyncDispatchExecutor = props.getProperty("nio.async.dispatch.executor", DEFAULT_NIO_ASYNC_DISPATCH_EXECUTOR);
        this.nioRegistrarPoolSize = getPositiveInt(props, "nio.registrar.pool.size", DEFAULT_NIO_REGISTRAR_POOL_SIZE);
        this.nioRegistrarQueueCapacity = getPositiveInt(props, "nio.registrar.queue.capacity", DEFAULT_NIO_REGISTRAR_QUEUE_CAPACITY);
        this.nioSslHandshakeTimeoutMs = getPositiveInt(props, "nio.ssl.handshake.timeout.ms", DEFAULT_NIO_SSL_HANDSHAKE_TIMEOUT_MS);

        this.sslEnabled = getBoolean(props, "ssl.enabled", DEFAULT_SSL_ENABLED);
        this.sslKeystorePath = props.getProperty("ssl.keystore.path", DEFAULT_SSL_KEYSTORE_PATH);
        this.sslKeystorePassword = props.getProperty("ssl.keystore.password", DEFAULT_SSL_KEYSTORE_PASSWORD);
        this.sslKeyPassword = props.getProperty("ssl.key.password", DEFAULT_SSL_KEY_PASSWORD);
        this.sslTruststorePath = props.getProperty("ssl.truststore.path", DEFAULT_SSL_TRUSTSTORE_PATH);
        this.sslTruststorePassword = props.getProperty("ssl.truststore.password", DEFAULT_SSL_TRUSTSTORE_PASSWORD);
    }

    /** Default config: loads {@code server.properties} from the classpath, or all defaults if missing. */
    public static ServerConfig defaults() {
        return fromClasspath("server.properties");
    }

    public static ServerConfig fromClasspath(String resource) {
        Properties props = new Properties();
        try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("Resource " + resource + " not found, using defaults.");
            } else {
                props.load(in);
            }
        } catch (IOException e) {
            log.error("Failed to load " + resource + ", using defaults.", e);
        }
        return new ServerConfig(props);
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public int getServerPort() {
        return serverPort;
    }

    public int getServerBacklog() {
        return serverBacklog;
    }

    public String getServerType() {
        return serverType;
    }

    public int getThreadedServerPoolSize() {
        return threadedServerPoolSize;
    }

    public int getNioNumIoReactors() {
        return nioNumIoReactors;
    }

    public int getNioNumIoThreads() {
        return nioNumIoThreads;
    }

    public boolean isNioAsyncDispatchEnabled() {
        return nioAsyncDispatchEnabled;
    }

    public String getNioAsyncDispatchExecutor() {
        return nioAsyncDispatchExecutor;
    }

    public int getNioRegistrarPoolSize() {
        return nioRegistrarPoolSize;
    }

    public int getNioRegistrarQueueCapacity() {
        return nioRegistrarQueueCapacity;
    }

    public int getNioSslHandshakeTimeoutMs() {
        return nioSslHandshakeTimeoutMs;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public String getSslKeystorePath() {
        return sslKeystorePath;
    }

    public String getSslKeystorePassword() {
        return sslKeystorePassword;
    }

    public String getSslKeyPassword() {
        return sslKeyPassword;
    }

    public String getSslTruststorePath() {
        return sslTruststorePath;
    }

    public String getSslTruststorePassword() {
        return sslTruststorePassword;
    }

    private static int getInt(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid integer for '" + key + "', using default " + defaultValue, e);
            }
        }
        return defaultValue;
    }

    /** Like {@link #getInt} but falls back to the default (with a warning) if the value is &le; 0,
     *  so a misconfigured count/size fails soft here instead of crashing later at pool/bind time. */
    private static int getPositiveInt(Properties props, String key, int defaultValue) {
        return getIntInRange(props, key, defaultValue, 1, Integer.MAX_VALUE);
    }

    private static int getIntInRange(Properties props, String key, int defaultValue, int min, int max) {
        int v = getInt(props, key, defaultValue);
        if (v < min || v > max) {
            log.warn("'" + key + "'=" + v + " out of range [" + min + ", " + max
                    + "], using default " + defaultValue);
            return defaultValue;
        }
        return v;
    }

    private static boolean getBoolean(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        return defaultValue;
    }
}
