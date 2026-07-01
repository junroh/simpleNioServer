package com.jun.http;

import org.junit.Test;

import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.*;

public class HttpHeadersTest {

    @Test
    public void getIsCaseInsensitive() {
        HttpHeaders h = new HttpHeaders();
        h.put("Content-Type", "text/plain");

        assertEquals("text/plain", h.get("Content-Type"));
        assertEquals("text/plain", h.get("CONTENT-TYPE"));
        assertEquals("text/plain", h.get("content-type"));
        assertEquals("text/plain", h.get("Content-type"));
    }

    @Test
    public void containsIsCaseInsensitive() {
        HttpHeaders h = new HttpHeaders();
        h.put("X-Custom", "v");
        assertTrue(h.contains("x-custom"));
        assertTrue(h.contains("X-CUSTOM"));
        assertFalse(h.contains("X-Other"));
    }

    @Test
    public void putWithDifferentCaseOverwritesSameKey() {
        HttpHeaders h = new HttpHeaders();
        h.put("Host", "first");
        h.put("HOST", "second");
        // Lookup is case-insensitive — second put replaces the value, not creates a new entry.
        assertEquals("second", h.get("host"));
    }

    @Test
    public void allReturnsInsertionOrder() {
        HttpHeaders h = new HttpHeaders();
        h.put("First", "1");
        h.put("Second", "2");
        h.put("Third", "3");

        Iterator<Map.Entry<String, String>> it = h.all().entrySet().iterator();
        assertEquals("First",  it.next().getKey());
        assertEquals("Second", it.next().getKey());
        assertEquals("Third",  it.next().getKey());
        assertFalse(it.hasNext());
    }

    @Test
    public void defaultsAreSensible() {
        HttpHeaders h = new HttpHeaders();
        assertEquals(-1, h.contentLength);
        assertFalse(h.chunkedTransferEncoding);
        assertTrue(h.keepAlive);
    }

    @Test
    public void getReturnsNullForMissing() {
        HttpHeaders h = new HttpHeaders();
        assertNull(h.get("Anything"));
    }
}
