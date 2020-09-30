package com.jun.nioServer.utility;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedThreadFactory implements ThreadFactory {

    private final String name;
    private final AtomicInteger id;

    public NamedThreadFactory(String name) {
        this.name = name;
        id = new AtomicInteger();
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, String.format("%s-%d", name, id.incrementAndGet()) );
    }
}
