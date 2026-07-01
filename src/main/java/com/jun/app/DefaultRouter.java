package com.jun.app;

import com.jun.config.ServerConfig;
import com.jun.http.HttpRouter;

/**
 * Default route table: wires the demo handlers onto a few well-known paths. Both server
 * transports build their handler from here when constructed from a {@link ServerConfig}.
 *
 * <p>For benchmarking, the {@code /sleep} route returns after sleeping for
 * {@code -Dbench.sleep.ms=<N>} milliseconds (default 0). Used by E3 and E7 to mimic a
 * blocking downstream call.
 */
public final class DefaultRouter {

    private static final String HELLO_BODY_FORMAT = "<html><body>Hello World(%d-%d)</body></html>";
    private static final String SLEEP_MS_PROP = "bench.sleep.ms";

    private DefaultRouter() {}

    public static HttpRouter fromConfig(ServerConfig _cfg) {
        SimpleHelloHandler hello = new SimpleHelloHandler(HELLO_BODY_FORMAT, 0);
        EchoHandler echo = new EchoHandler();
        SlowHandler slow = new SlowHandler(sleepMsFromSystemProperty());
        return new HttpRouter()
                .get("/", hello)
                .get("/hello", hello)
                .get("/echo", echo)
                .post("/echo", echo)
                .get("/sleep", slow)
                .post("/sleep", slow);
    }

    private static long sleepMsFromSystemProperty() {
        String v = System.getProperty(SLEEP_MS_PROP);
        if (v == null) {
            return 0L;
        }
        try {
            long ms = Long.parseLong(v.trim());
            if (ms < 0) {
                return 0L;
            }
            return ms;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
