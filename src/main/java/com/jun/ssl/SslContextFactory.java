package com.jun.ssl;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Builds an {@link SSLContext} from on-disk keystore / truststore files. Used by both
 * the NIO transport (via {@link com.jun.nioServer.ssl.SSLEngineBuffer}) and the threaded
 * transport (via {@link javax.net.ssl.SSLServerSocketFactory}).
 */
public final class SslContextFactory {

    private SslContextFactory() {
    }

    public static SSLContext build(String keystorePath,
                                   String keystorePassword,
                                   String keyPassword,
                                   String truststorePath,
                                   String truststorePassword) throws IOException, GeneralSecurityException {
        KeyManager[] keyManagers = createKeyManagers(keystorePath, keystorePassword, keyPassword);
        TrustManager[] trustManagers = createTrustManagers(truststorePath, truststorePassword);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keyManagers, trustManagers, null);
        return ctx;
    }

    public static KeyManager[] createKeyManagers(String filepath,
                                                 String keystorePassword,
                                                 String keyPassword) throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(Paths.get(filepath))) {
            keyStore.load(in, keystorePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword.toCharArray());
        return kmf.getKeyManagers();
    }

    public static TrustManager[] createTrustManagers(String filepath,
                                                     String truststorePassword) throws IOException, GeneralSecurityException {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(Paths.get(filepath))) {
            trustStore.load(in, truststorePassword.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf.getTrustManagers();
    }
}
