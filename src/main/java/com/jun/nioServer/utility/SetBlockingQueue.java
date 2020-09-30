package com.jun.nioServer.utility;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class SetBlockingQueue<T> extends LinkedBlockingQueue<T> {

    private final Set<T> set = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Override
    public synchronized boolean offer(T t) {
        if (!set.contains(t)) {
            boolean ret = super.offer(t);
            if(ret) {
                set.add(t);
            }
            return ret;
        }
        return true;
    }

    // todo: this is not thread safe with offer
    @Override
    public T take() throws InterruptedException {
        T t = super.take();
        set.remove(t);
        return t;
    }
}
