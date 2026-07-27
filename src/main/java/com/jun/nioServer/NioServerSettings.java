package com.jun.nioServer;

import com.jun.config.ServerConfig;

/**
 * Immutable settings consumed by {@link NioServerService}. Decouples the service from
 * {@link ServerConfig} so tests can construct arbitrary configurations directly via
 * {@link #builder()}.
 *
 * @param port                  TCP port to bind
 * @param hostAddress           host to bind ("localhost", "0.0.0.0", etc.)
 * @param sslEnabled            true → TLS via the keystore/truststore fields
 * @param keystorePath          JKS containing the server cert
 * @param keystorePassword      password for the keystore
 * @param keyPassword           password for the private key entry
 * @param truststorePath        JKS of trusted CAs (mTLS-ready, unused for plain TLS)
 * @param truststorePassword    password for the truststore
 * @param numIoReactors         number of {@link IOReactor}s (each on its own thread)
 * @param numIoThreads          I/O pool size per reactor
 * @param inlineIo              run plaintext read/write inline on the reactor thread instead of
 *                              offloading to the I/O pool (ADR 0005); TLS connections still offload
 * @param acceptorBacklog       OS accept-queue depth
 * @param acceptorNonBlocking   {@code ServerSocketChannel.configureBlocking()} inverse
 * @param clientSocketBlocking  blocking mode for accepted client sockets (almost always false for NIO)
 * @param maxBytesPerRead       soft cap on bytes pulled per dispatch; prevents a single chatty
 *                              connection from monopolising an I/O-pool thread
 * @param maxBytesPerWrite      soft cap on bytes pushed per write turn; the write yields and
 *                              re-arms OP_WRITE after it, so a large response cannot monopolise
 *                              an I/O worker (or, in an inline transport, the reactor thread)
 * @param registrarPoolSize     max concurrent connection registrations / SSL handshakes
 * @param registrarQueueCapacity registrar queue depth before new connections are rejected
 * @param sslHandshakeTimeoutMs watchdog deadline for a single SSL handshake (ms)
 */
public record NioServerSettings(
        int port,
        String hostAddress,
        boolean sslEnabled,
        String keystorePath,
        String keystorePassword,
        String keyPassword,
        String truststorePath,
        String truststorePassword,
        int numIoReactors,
        int numIoThreads,
        boolean inlineIo,
        int acceptorBacklog,
        boolean acceptorNonBlocking,
        boolean clientSocketBlocking,
        int maxBytesPerRead,
        int maxBytesPerWrite,
        int registrarPoolSize,
        int registrarQueueCapacity,
        int sslHandshakeTimeoutMs) {

    public static NioServerSettings fromServerConfig(ServerConfig cfg) {
        return builder()
                .port(cfg.getServerPort())
                .hostAddress(cfg.getServerAddress())
                .sslEnabled(cfg.isSslEnabled())
                .keystorePath(cfg.getSslKeystorePath())
                .keystorePassword(cfg.getSslKeystorePassword())
                .keyPassword(cfg.getSslKeyPassword())
                .truststorePath(cfg.getSslTruststorePath())
                .truststorePassword(cfg.getSslTruststorePassword())
                .numIoReactors(cfg.getNioNumIoReactors())
                .numIoThreads(cfg.getNioNumIoThreads())
                .inlineIo(cfg.isNioIoInline())
                .acceptorBacklog(cfg.getServerBacklog())
                .registrarPoolSize(cfg.getNioRegistrarPoolSize())
                .registrarQueueCapacity(cfg.getNioRegistrarQueueCapacity())
                .sslHandshakeTimeoutMs(cfg.getNioSslHandshakeTimeoutMs())
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int port = 8080;
        private String hostAddress = "localhost";
        private boolean sslEnabled = false;
        private String keystorePath;
        private String keystorePassword;
        private String keyPassword;
        private String truststorePath;
        private String truststorePassword;
        private int numIoReactors = 1;
        private int numIoThreads = 4;
        private boolean inlineIo = false;
        private int acceptorBacklog = 1024;
        private boolean acceptorNonBlocking = true;
        private boolean clientSocketBlocking = false;
        private int maxBytesPerRead = 64 * 1024;
        private int maxBytesPerWrite = 64 * 1024;
        private int registrarPoolSize = 64;
        private int registrarQueueCapacity = 256;
        private int sslHandshakeTimeoutMs = 10_000;

        public Builder port(int v)                  { this.port = v;                  return this; }
        public Builder hostAddress(String v)        { this.hostAddress = v;           return this; }
        public Builder sslEnabled(boolean v)        { this.sslEnabled = v;            return this; }
        public Builder keystorePath(String v)       { this.keystorePath = v;          return this; }
        public Builder keystorePassword(String v)   { this.keystorePassword = v;      return this; }
        public Builder keyPassword(String v)        { this.keyPassword = v;           return this; }
        public Builder truststorePath(String v)     { this.truststorePath = v;        return this; }
        public Builder truststorePassword(String v) { this.truststorePassword = v;    return this; }
        public Builder numIoReactors(int v)         { this.numIoReactors = v;         return this; }
        public Builder numIoThreads(int v)          { this.numIoThreads = v;          return this; }
        public Builder inlineIo(boolean v)          { this.inlineIo = v;              return this; }
        public Builder acceptorBacklog(int v)       { this.acceptorBacklog = v;       return this; }
        public Builder acceptorNonBlocking(boolean v)  { this.acceptorNonBlocking = v;  return this; }
        public Builder clientSocketBlocking(boolean v) { this.clientSocketBlocking = v; return this; }
        public Builder maxBytesPerRead(int v)       { this.maxBytesPerRead = v;       return this; }
        public Builder maxBytesPerWrite(int v)      { this.maxBytesPerWrite = v;      return this; }
        public Builder registrarPoolSize(int v)     { this.registrarPoolSize = v;     return this; }
        public Builder registrarQueueCapacity(int v){ this.registrarQueueCapacity = v; return this; }
        public Builder sslHandshakeTimeoutMs(int v) { this.sslHandshakeTimeoutMs = v; return this; }

        public NioServerSettings build() {
            return new NioServerSettings(
                    port, hostAddress, sslEnabled,
                    keystorePath, keystorePassword, keyPassword,
                    truststorePath, truststorePassword,
                    numIoReactors, numIoThreads, inlineIo,
                    acceptorBacklog, acceptorNonBlocking, clientSocketBlocking,
                    maxBytesPerRead,
                    maxBytesPerWrite,
                    registrarPoolSize, registrarQueueCapacity, sslHandshakeTimeoutMs);
        }
    }
}
