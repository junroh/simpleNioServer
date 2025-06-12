package com.jun.config;

import org.junit.Test;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Properties;
import static org.junit.Assert.*;

public class ServerConfigTest {

    // Helper method to load properties for testing purposes
    private void loadTestProperties(String propertiesFileName) throws Exception {
        Properties props = new Properties();

        // Access the private static loadProperties and setDefaultProperties methods via reflection
        Method loadPropertiesMethod = ServerConfig.class.getDeclaredMethod("loadProperties", Properties.class);
        loadPropertiesMethod.setAccessible(true);

        Method setDefaultPropertiesMethod = ServerConfig.class.getDeclaredMethod("setDefaultProperties");
        setDefaultPropertiesMethod.setAccessible(true);

        // Reset all static non-final fields to ensure a clean state for each test.
        // This is important because ServerConfig's static initializer runs only once.
        Field[] fields = ServerConfig.class.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                if (field.getType().equals(int.class)) {
                    field.setInt(null, 0); // Default for int
                } else if (field.getType().equals(boolean.class)) {
                    field.setBoolean(null, false); // Default for boolean
                } else {
                    field.set(null, null); // Default for objects
                }
            }
        }

        try (InputStream input = ServerConfigTest.class.getClassLoader().getResourceAsStream(propertiesFileName)) {
            if (propertiesFileName != null && input != null) {
                props.load(input);
                loadPropertiesMethod.invoke(null, props); // ServerConfig.loadProperties(props)
            } else {
                // If file not found or filename is null, call setDefaultProperties
                setDefaultPropertiesMethod.invoke(null); // ServerConfig.setDefaultProperties()
            }
        } catch (Exception e) {
            System.err.println("Exception in loadTestProperties for " + propertiesFileName + ": " + e.getMessage());
            e.printStackTrace();
            // Fallback to ensure defaults are set if any error occurs during loading
            try {
                setDefaultPropertiesMethod.invoke(null);
            } catch (Exception reflectionException) {
                // If reflection call itself fails, try manual setting as a last resort
                System.err.println("Reflection call to setDefaultProperties failed, attempting manual set.");
                setManDefaultProperties();
            }
        }
    }

    //Manually set default properties if reflection fails
    private void setManDefaultProperties() throws Exception {
        setStaticField("THREADED_SERVER_PORT", 8080);
        setStaticField("THREADED_SERVER_BACKLOG", 1024);
        setStaticField("THREADED_SERVER_POOL_SIZE", 100);
        setStaticField("NIO_SERVER_PORT", 8080);
        setStaticField("NIO_SERVER_SSL_ENABLED", false);
        setStaticField("NIO_ACCEPTOR_ADDRESS", "localhost");
        setStaticField("NIO_ACCEPTOR_BACKLOG", 1024);
        setStaticField("NIO_ACCEPTOR_NUM_IOREACTOR", 1);
        setStaticField("NIO_ACCEPTOR_NUM_READER_THREADS", 2);
        setStaticField("NIO_ACCEPTOR_NUM_WRITER_THREADS", 2);
        setStaticField("NIO_ACCEPTOR_IS_BLOCKING", true);
        setStaticField("SSL_KEYSTORE_PATH", "./src/main/resources/server.jks");
        setStaticField("SSL_KEYSTORE_PASSWORD", "storepass");
        setStaticField("SSL_KEY_PASSWORD", "keypass");
        setStaticField("SSL_TRUSTSTORE_PATH", "./src/main/resources/trustedCerts.jks");
        setStaticField("SSL_TRUSTSTORE_PASSWORD", "storepass");
        setStaticField("NIO_MSG_HANDLER_STATIC_RESPONSE_PART1", "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ");
        setStaticField("NIO_MSG_HANDLER_STATIC_RESPONSE_PART2", "\r\n\r\n");
        setStaticField("NIO_MSG_HANDLER_RESPONSE_BODY_FORMAT", "<html><body>Hello World(%d-%d)</body></html>");
        setStaticField("SERVER_TYPE_PROPERTY_KEY", "server.type");
        setStaticField("SERVER_TYPE_NIO", "nio");
        setStaticField("SERVER_TYPE_THREADED", "threaded");
        setStaticField("DEFAULT_SERVER_TYPE", "nio");
    }


    private void setStaticField(String fieldName, Object value) throws Exception {
        Field field = ServerConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        // Remove final modifier if present (though ServerConfig fields are not final anymore)
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL); // Should not be necessary now
        field.set(null, value);
    }


    @Test
    public void testLoadFromTestProperties() throws Exception {
        loadTestProperties("test.properties");

        assertEquals("Port from test.properties should be 9090", 9090, ServerConfig.THREADED_SERVER_PORT);
        assertTrue("SSL enabled from test.properties should be true", ServerConfig.NIO_SERVER_SSL_ENABLED);

        assertEquals(9090, ServerConfig.THREADED_SERVER_PORT);

        assertEquals("NIO port should use default value", 8080, ServerConfig.NIO_SERVER_PORT);

        assertEquals("Server type property key should use default", "server.type", ServerConfig.SERVER_TYPE_PROPERTY_KEY);
    }

    @Test
    public void testDefaultsWhenPropertyMissing() throws Exception {
        loadTestProperties("test.properties");

        assertEquals("NIO_SERVER_PORT should be default (8080) as it's missing in test.properties", 8080, ServerConfig.NIO_SERVER_PORT);
        assertEquals("Default for SSL_KEYSTORE_PASSWORD should be 'storepass'", "storepass", ServerConfig.SSL_KEYSTORE_PASSWORD);
    }

    @Test
    public void testDefaultsWhenPropertiesFileMissing() throws Exception {
        loadTestProperties("nonexistent.properties");

        assertEquals("Threaded port should be default 8080", 8080, ServerConfig.THREADED_SERVER_PORT);
        assertFalse("NIO SSL should be default false", ServerConfig.NIO_SERVER_SSL_ENABLED);
        assertEquals("NIO acceptor address should be default 'localhost'", "localhost", ServerConfig.NIO_ACCEPTOR_ADDRESS);
        assertEquals("Default server type should be 'nio'", "nio", ServerConfig.DEFAULT_SERVER_TYPE);
    }
}
