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

    // TODO: This method is not synchronized, while 'offer' is. Review for thread-safety implications regarding the atomicity of queue and set operations across 'offer' and 'take'.
    @Override
    public T take() throws InterruptedException {
        T t = super.take();
        set.remove(t);
        return t;
    }
}
