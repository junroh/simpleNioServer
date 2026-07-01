package com.jun.integration.support;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Shared HTTP/HTTPS client helpers for integration tests. Stateless — all methods are
 * static, callers pass URLs and bodies.
 *
 * <p>Client quirks handled here so tests don't repeat them:
 * <ul>
 *   <li>Forces HTTP/1.1 — the demo server is HTTP/1.1 only; default HTTP/2 negotiation
 *       can stall on some methods.</li>
 *   <li>For HTTPS, disables hostname verification via a JDK-internal sysprop so the
 *       self-signed test cert (no SAN) is accepted.</li>
 *   <li>PUT uses {@code expectContinue(false)} — JDK HttpClient otherwise issues
 *       {@code Expect: 100-continue} and waits for a 100 the demo server never sends.</li>
 * </ul>
 */
public final class TestClients {

    public static final String PATH_HELLO        = "/hello";
    public static final String PATH_ECHO         = "/echo";
    public static final String PATH_ECHO_QUERY   = "/echo?msg=hi";
    public static final String PATH_MISSING      = "/missing";
    public static final String ECHO_QUERY_MARKER = "msg=hi";
    public static final String HELLO_BODY_MARKER = "Hello World";

    public static final int HTTP_OK        = 200;
    public static final int HTTP_NOT_FOUND = 404;

    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    private static final String SYSPROP_DISABLE_HOSTNAME_VERIFICATION =
            "jdk.internal.httpclient.disableHostnameVerification";

    static {
        // HttpClient ignores SSLParameters.setEndpointIdentificationAlgorithm("") —
        // it hard-codes "HTTPS" internally. This sysprop is the documented escape hatch.
        // Set as early as possible (class init).
        System.setProperty(SYSPROP_DISABLE_HOSTNAME_VERIFICATION, "true");
    }

    private static final String LOCALHOST = "localhost";

    private TestClients() {}

    public static String httpUrl(int port, String path) {
        return "http://" + LOCALHOST + ":" + port + path;
    }

    public static String httpsUrl(int port, String path) {
        return "https://" + LOCALHOST + ":" + port + path;
    }

    public static HttpResponse<String> get(String url) throws Exception {
        return send(httpClient(), HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT).GET().build());
    }

    public static HttpResponse<String> post(String url, String body) throws Exception {
        return send(httpClient(), HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    public static HttpResponse<String> put(String url, String body) throws Exception {
        return send(httpClient(), HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .expectContinue(false)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    public static HttpResponse<String> httpsGet(String url) throws Exception {
        return send(trustingHttpsClient(), HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT).GET().build());
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest req) throws Exception {
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /** Accepts any certificate + skips hostname verification. Test-only — never in production. */
    private static HttpClient trustingHttpsClient() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        }}, new SecureRandom());

        SSLParameters params = new SSLParameters();
        params.setEndpointIdentificationAlgorithm("");

        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .sslContext(ctx)
                .sslParameters(params)
                .build();
    }

    public static void assertHelloOk(HttpResponse<String> resp) {
        assertEquals(HTTP_OK, resp.statusCode());
        assertTrue("body should contain '" + HELLO_BODY_MARKER + "', got: " + resp.body(),
                resp.body().contains(HELLO_BODY_MARKER));
    }
}
