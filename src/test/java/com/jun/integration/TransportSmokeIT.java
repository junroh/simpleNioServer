package com.jun.integration;

import com.jun.integration.support.TestServers;
import org.junit.After;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static com.jun.integration.support.TestClients.PATH_HELLO;
import static com.jun.integration.support.TestClients.assertHelloOk;
import static com.jun.integration.support.TestClients.get;
import static com.jun.integration.support.TestClients.httpUrl;

/**
 * Each transport starts and answers a basic HTTP GET. The minimum bar — if this fails
 * for a transport, none of the deeper tests for it can be trusted.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TransportSmokeIT {

    private final TestServers servers = new TestServers();

    @After public void tearDown() { servers.stopAll(); }

    @Test
    public void threaded() throws Exception {
        int port = servers.startThreaded(false, null);
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void loomThreaded() throws Exception {
        int port = servers.startThreaded(true, null);
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void nio() throws Exception {
        int port = servers.startNio(false, false);
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }

    @Test
    public void nioAsync() throws Exception {
        int port = servers.startNio(false, true);
        assertHelloOk(get(httpUrl(port, PATH_HELLO)));
    }
}
