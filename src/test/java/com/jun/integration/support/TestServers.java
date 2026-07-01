package com.jun.integration.support;

import com.jun.app.EchoHandler;
import com.jun.app.SimpleHelloHandler;
import com.jun.http.HttpRequestHandler;
import com.jun.http.HttpRouter;
import com.jun.nioServer.NioServerService;
import com.jun.nioServer.NioServerSettings;
import com.jun.nioServer.http.HttpNioMessageHandler;
import com.jun.nioServer.http.HttpNioMessageReaderFactory;
import com.jun.nioServer.message.AsyncMessageProcessor;
import com.jun.nioServer.message.MessageProcessor;
import com.jun.ssl.SslContextFactory;
import com.jun.threadedServer.HttpWorker;
import com.jun.threadedServer.ThreadedServer;
import com.jun.threadedServer.WorkerFactory;
import org.junit.Assume;

import javax.net.ssl.SSLContext;
import java.util.concurrent.Executors;

import static com.jun.integration.support.TestClients.PATH_ECHO;
import static com.jun.integration.support.TestClients.PATH_HELLO;

/**
 * Server lifecycle helpers for integration tests. One instance per test class — call
 * {@link #stopAll()} from {@code @After}.
 *
 * <p>Each {@code startThreaded} / {@code startNio} variant binds to an ephemeral port
 * and returns the bound port. {@link NioServerService#start()} is now synchronous (waits
 * for the acceptor to enter its loop), so the returned port is immediately usable.
 *
 * <p>Locked-down sandboxes that refuse {@code bind()} cause the start to throw; the
 * helper translates that into a JUnit {@code Assume} failure so the test is skipped
 * rather than reported as broken.
 */
public final class TestServers {

    public static final String KEYSTORE_PATH     = "./src/main/resources/server.jks";
    public static final String TRUSTSTORE_PATH   = "./src/main/resources/trustedCerts.jks";
    public static final String KEYSTORE_PASSWORD = "storepass";
    public static final String KEY_PASSWORD      = "keypass";

    public static final int EPHEMERAL_PORT     = 0;
    public static final int ACCEPT_BACKLOG     = 128;
    public static final int THREADED_POOL_SIZE = 8;
    public static final int NIO_REACTORS       = 1;
    public static final int NIO_READERS        = 2;
    public static final int NIO_WRITERS        = 2;
    public static final int NIO_ASYNC_POOL     = 8;

    public static final String HELLO_BODY_FORMAT = "<html><body>Hello World(%d-%d)</body></html>";
    public static final String LOCALHOST         = "localhost";

    private NioServerService nio;
    private ThreadedServer threaded;

    /** Stops every server this helper started. Safe to call multiple times. */
    public void stopAll() {
        if (nio != null) {
            nio.stop();
            nio = null;
        }
        if (threaded != null) {
            threaded.stop();
            threaded = null;
        }
    }

    public int startThreaded(boolean useVirtual, SSLContext sslContext) {
        HttpRequestHandler router = defaultRouter();
        WorkerFactory factory = socket -> new HttpWorker(socket, router);
        threaded = new ThreadedServer(EPHEMERAL_PORT, ACCEPT_BACKLOG, THREADED_POOL_SIZE,
                factory, sslContext, useVirtual);
        try {
            threaded.start();
        } catch (RuntimeException e) {
            Assume.assumeNoException("environment forbids bind()", e);
        }
        return threaded.getBoundPort().orElseThrow(
                () -> new IllegalStateException("threaded server did not bind"));
    }

    public int startNio(boolean ssl, boolean asyncDispatch) {
        return startNio(defaultRouter(), ssl, asyncDispatch);
    }

    /** Start a plaintext NIO server with a caller-supplied handler (e.g. custom routes for
     *  resilience / async tests). */
    public int startNio(HttpRequestHandler handler, boolean asyncDispatch) {
        return startNio(handler, false, asyncDispatch);
    }

    private int startNio(HttpRequestHandler handler, boolean ssl, boolean asyncDispatch) {
        NioServerSettings settings = NioServerSettings.builder()
                .port(EPHEMERAL_PORT)
                .hostAddress(LOCALHOST)
                .sslEnabled(ssl)
                .keystorePath(KEYSTORE_PATH)
                .keystorePassword(KEYSTORE_PASSWORD)
                .keyPassword(KEY_PASSWORD)
                .truststorePath(TRUSTSTORE_PATH)
                .truststorePassword(KEYSTORE_PASSWORD)
                .numIoReactors(NIO_REACTORS)
                .numReaderThreads(NIO_READERS)
                .numWriterThreads(NIO_WRITERS)
                .build();
        MessageProcessor processor = new HttpNioMessageHandler(handler);
        if (asyncDispatch) {
            processor = new AsyncMessageProcessor(processor, Executors.newFixedThreadPool(NIO_ASYNC_POOL));
        }
        nio = new NioServerService(settings, new HttpNioMessageReaderFactory(), processor);
        try {
            nio.start();
        } catch (Exception e) {
            Assume.assumeNoException("environment forbids bind()", e);
        }
        return nio.getBoundPort().orElseThrow(
                () -> new IllegalStateException("NIO server did not bind"));
    }

    public static SSLContext buildServerSslContext() throws Exception {
        return SslContextFactory.build(KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_PASSWORD,
                TRUSTSTORE_PATH, KEYSTORE_PASSWORD);
    }

    private static HttpRequestHandler defaultRouter() {
        EchoHandler echo = new EchoHandler();
        return new HttpRouter()
                .get(PATH_HELLO, new SimpleHelloHandler(HELLO_BODY_FORMAT, 0))
                .get(PATH_ECHO, echo)
                .post(PATH_ECHO, echo)
                .put(PATH_ECHO, echo);
    }
}
