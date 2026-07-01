package com.jun.ssl;

import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.IOException;
import java.security.UnrecoverableKeyException;

import static org.junit.Assert.*;

/**
 * Tests for {@link SslContextFactory}. The factory is shared by both transports — these
 * tests prove it produces a usable {@link SSLContext} from the project's existing
 * test keystore, and surfaces clear errors on bad inputs.
 */
public class SslContextFactoryTest {

    private static final String KEYSTORE      = "./src/main/resources/server.jks";
    private static final String TRUSTSTORE    = "./src/main/resources/trustedCerts.jks";
    private static final String STORE_PASS    = "storepass";
    private static final String KEY_PASS      = "keypass";

    @Test
    public void buildsUsableSslContext() throws Exception {
        SSLContext ctx = SslContextFactory.build(
                KEYSTORE, STORE_PASS, KEY_PASS, TRUSTSTORE, STORE_PASS);

        assertNotNull(ctx);
        assertEquals("TLS", ctx.getProtocol());

        // Smoke-test: the context can vend a server socket factory (used by ThreadedServer).
        SSLServerSocketFactory factory = ctx.getServerSocketFactory();
        assertNotNull(factory);
        assertTrue("Server cipher suites should not be empty",
                factory.getSupportedCipherSuites().length > 0);

        // And an SSLEngine (used by NIO).
        assertNotNull(ctx.createSSLEngine());
    }

    @Test
    public void createKeyManagersReturnsManagers() throws Exception {
        assertTrue(SslContextFactory.createKeyManagers(KEYSTORE, STORE_PASS, KEY_PASS).length > 0);
    }

    @Test
    public void createTrustManagersReturnsManagers() throws Exception {
        assertTrue(SslContextFactory.createTrustManagers(TRUSTSTORE, STORE_PASS).length > 0);
    }

    @Test(expected = IOException.class)
    public void missingKeystoreFileThrows() throws Exception {
        SslContextFactory.build(
                "./does-not-exist.jks", STORE_PASS, KEY_PASS,
                TRUSTSTORE, STORE_PASS);
    }

    @Test
    public void wrongKeystorePasswordThrows() {
        try {
            SslContextFactory.build(
                    KEYSTORE, "wrong-password", KEY_PASS,
                    TRUSTSTORE, STORE_PASS);
            fail("expected an exception");
        } catch (Exception e) {
            // The JDK throws IOException("keystore password was incorrect") wrapping a
            // UnrecoverableKeyException — we don't care about the exact subtype, only that
            // a wrong password isn't silently accepted.
            assertTrue(e instanceof IOException || e instanceof UnrecoverableKeyException);
        }
    }
}
