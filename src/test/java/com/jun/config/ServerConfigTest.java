package com.jun.config;

import org.junit.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.Assert.*;

/**
 * Constructor-driven tests for {@link ServerConfig}. No reflection, no static-state mutation —
 * each test builds its own config from a {@link Properties} or a classpath resource.
 */
public class ServerConfigTest {

    private static Properties loadFromClasspath(String name) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ServerConfigTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in != null) p.load(in);
        }
        return p;
    }

    @Test
    public void loadsFromTestProperties() throws Exception {
        ServerConfig cfg = new ServerConfig(loadFromClasspath("test.properties"));

        assertEquals("server.port from test.properties", 9090, cfg.getServerPort());
        assertTrue("ssl.enabled from test.properties", cfg.isSslEnabled());
    }

    @Test
    public void missingKeysFallBackToDefaults() throws Exception {
        ServerConfig cfg = new ServerConfig(loadFromClasspath("test.properties"));

        // test.properties only overrides some keys; the rest must come from documented defaults.
        assertEquals("server.address default", "localhost", cfg.getServerAddress());
        assertEquals("server.backlog default", 1024, cfg.getServerBacklog());
        assertEquals("ssl.keystore.password default", "storepass", cfg.getSslKeystorePassword());
    }

    @Test
    public void emptyPropertiesGivesAllDefaults() {
        ServerConfig cfg = new ServerConfig(new Properties());

        assertEquals(8080, cfg.getServerPort());
        assertEquals("localhost", cfg.getServerAddress());
        assertEquals(1024, cfg.getServerBacklog());
        assertEquals(ServerConfig.TYPE_NIO, cfg.getServerType());
        assertFalse(cfg.isSslEnabled());
        assertFalse(cfg.isNioAsyncDispatchEnabled());
        assertEquals("fixed:500", cfg.getNioAsyncDispatchExecutor());
    }

    @Test
    public void nullPropertiesGivesAllDefaults() {
        // null Properties must not NPE — it maps to "empty", i.e. all documented defaults.
        ServerConfig cfg = new ServerConfig(null);
        assertEquals(8080, cfg.getServerPort());
    }

    @Test
    public void fromClasspath_missingResource_returnsDefaults() {
        ServerConfig cfg = ServerConfig.fromClasspath("definitely-not-here.properties");
        assertEquals(8080, cfg.getServerPort());
        assertEquals(ServerConfig.TYPE_NIO, cfg.getServerType());
    }

    @Test
    public void invalidIntFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("server.port", "not-a-number");
        ServerConfig cfg = new ServerConfig(p);
        assertEquals(8080, cfg.getServerPort());
    }

    @Test
    public void ioThreadsDefault() {
        ServerConfig cfg = new ServerConfig(new Properties());
        assertEquals(4, cfg.getNioNumIoThreads());
        assertEquals(1, cfg.getNioNumIoReactors());
    }

    @Test
    public void ioThreadsOverride() {
        Properties p = new Properties();
        p.setProperty("nio.num.io.threads", "8");
        ServerConfig cfg = new ServerConfig(p);
        assertEquals(8, cfg.getNioNumIoThreads());
    }

    @Test
    public void ioInlineDefaultsOff() {
        assertFalse(new ServerConfig(new Properties()).isNioIoInline());
    }

    @Test
    public void ioInlineCanBeEnabled() {
        Properties p = new Properties();
        p.setProperty("nio.io.inline", "true");
        assertTrue(new ServerConfig(p).isNioIoInline());
    }
}
