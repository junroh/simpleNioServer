package com.jun.http;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HttpRouterTest {

    private static HttpRequest request(String method, String uri) {
        return new HttpRequest(method, uri, "HTTP/1.1", new HttpHeaders(), new byte[0]);
    }

    private static String bodyAsString(HttpResponse resp) {
        byte[] wire = resp.toBytes();
        String s = new String(wire, StandardCharsets.UTF_8);
        int blank = s.indexOf("\r\n\r\n");
        return s.substring(blank + 4);
    }

    private static int statusOf(HttpResponse resp) {
        String s = new String(resp.toBytes(), StandardCharsets.UTF_8);
        // status-line: "HTTP/1.1 NNN Reason\r\n..."
        int firstSpace = s.indexOf(' ');
        int secondSpace = s.indexOf(' ', firstSpace + 1);
        return Integer.parseInt(s.substring(firstSpace + 1, secondSpace));
    }

    @Test
    public void exactMatchDispatchesToRegisteredHandler() {
        HttpRouter router = new HttpRouter()
                .get("/hello", req -> new HttpResponse().status(200, "OK").body("hi"));

        HttpResponse resp = router.handle(request("GET", "/hello"));
        assertEquals("hi", bodyAsString(resp));
    }

    @Test
    public void methodAndPathBothMatter() {
        HttpRouter router = new HttpRouter()
                .get("/users", req -> new HttpResponse().status(200, "OK").body("list"))
                .post("/users", req -> new HttpResponse().status(201, "Created").body("created"));

        assertEquals("list",    bodyAsString(router.handle(request("GET",  "/users"))));
        assertEquals("created", bodyAsString(router.handle(request("POST", "/users"))));
        // PUT is not registered → 404
        assertEquals(404, statusOf(router.handle(request("PUT", "/users"))));
    }

    @Test
    public void unregisteredPathReturns404() {
        HttpRouter router = new HttpRouter();
        HttpResponse resp = router.handle(request("GET", "/missing"));
        assertEquals(404, statusOf(resp));
        assertEquals("Not Found", bodyAsString(resp));
    }

    @Test
    public void customNotFoundHandlerOverridesDefault() {
        HttpRouter router = new HttpRouter()
                .notFound(req -> new HttpResponse().status(418, "I'm a teapot").body("teapot"));

        HttpResponse resp = router.handle(request("GET", "/anything"));
        assertEquals(418, statusOf(resp));
        assertEquals("teapot", bodyAsString(resp));
    }

    @Test
    public void prefixMatchAppliesAfterExactMisses() {
        HttpRouter router = new HttpRouter()
                .prefix("/api/", req -> new HttpResponse().status(200, "OK").body("api"));

        // Exact match is missing → falls through to prefix.
        assertEquals("api", bodyAsString(router.handle(request("GET", "/api/users"))));
        assertEquals("api", bodyAsString(router.handle(request("POST","/api/orders"))));
        // Path that doesn't match the prefix → 404.
        assertEquals(404, statusOf(router.handle(request("GET", "/notapi"))));
    }

    @Test
    public void exactMatchBeatsPrefix() {
        AtomicInteger exactCalls = new AtomicInteger();
        AtomicInteger prefixCalls = new AtomicInteger();

        HttpRouter router = new HttpRouter()
                .get("/api/health", req -> {
                    exactCalls.incrementAndGet();
                    return new HttpResponse().body("ok");
                })
                .prefix("/api/", req -> {
                    prefixCalls.incrementAndGet();
                    return new HttpResponse().body("api");
                });

        router.handle(request("GET", "/api/health"));
        assertEquals(1, exactCalls.get());
        assertEquals(0, prefixCalls.get());
    }

    @Test
    public void queryStringStripped() {
        HttpRouter router = new HttpRouter()
                .get("/search", req -> new HttpResponse().body("ok"));

        // /search?q=foo should still match /search.
        HttpResponse resp = router.handle(request("GET", "/search?q=foo&page=2"));
        assertEquals("ok", bodyAsString(resp));
    }

    @Test
    public void overwritingSameRouteReplacesHandler() {
        HttpRouter router = new HttpRouter()
                .get("/x", req -> new HttpResponse().body("first"))
                .get("/x", req -> new HttpResponse().body("second"));

        assertEquals("second", bodyAsString(router.handle(request("GET", "/x"))));
    }

    @Test
    public void rejectsNullArguments() {
        HttpRouter router = new HttpRouter();
        try { router.route(null, "/p", req -> null); fail(); } catch (NullPointerException ok) {}
        try { router.route("GET", null, req -> null); fail(); } catch (NullPointerException ok) {}
        try { router.route("GET", "/p", null);        fail(); } catch (NullPointerException ok) {}
        try { router.prefix(null, req -> null);       fail(); } catch (NullPointerException ok) {}
        try { router.prefix("/api/", null);           fail(); } catch (NullPointerException ok) {}
    }

    @Test
    public void delegatesToDifferentHttpMethods() {
        // Smoke-test the delete/put/head shortcuts.
        HttpRouter router = new HttpRouter()
                .put("/p",    req -> new HttpResponse().body("put"))
                .delete("/p", req -> new HttpResponse().body("delete"))
                .head("/p",   req -> new HttpResponse().body("head"));
        assertEquals("put",    bodyAsString(router.handle(request("PUT",    "/p"))));
        assertEquals("delete", bodyAsString(router.handle(request("DELETE", "/p"))));
        assertEquals("head",   bodyAsString(router.handle(request("HEAD",   "/p"))));
    }
}
