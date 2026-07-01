package com.jun.integration;

import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.assertHelloOk;
import static com.jun.integration.support.TestClients.httpUrl;
import static com.jun.integration.support.TestClients.httpsGet;
import static com.jun.integration.support.TestClients.httpsUrl;

/**
 * TLS over each transport. Threaded uses {@code SSLServerSocket}; NIO drives
 * {@link com.jun.nioServer.ssl.SSLEngineBuffer}. The async-dispatch variant verifies
 * the wrapper does not break the TLS path.
 *
 * <p>The HTTPS client trusts any cert AND skips hostname verification (the test cert
 * has no {@code localhost} SAN). See {@code TestClients} for the configured client.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HttpsIT {

    private final TestServers servers = new TestServers();

    @After public void tearDown() { servers.stopAll(); }

    @Test
    public void threaded_https() throws Exception {
        int port = servers.startThreaded(false, TestServers.buildServerSslContext());
        assertHelloOk(httpsGet(httpsUrl(port, PATH_HELLO)));
    }

    @Test
    public void nio_https() throws Exception {
        int port = servers.startNio(true, false);
        assertHelloOk(httpsGet(httpsUrl(port, PATH_HELLO)));
    }

    @Test
    public void nioAsync_https() throws Exception {
        int port = servers.startNio(true, true);
        assertHelloOk(httpsGet(httpsUrl(port, PATH_HELLO)));
    }
}
