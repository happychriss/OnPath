package org.teavm.classlib.java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.locks.ReentrantLock for TeaVM (its class library has none). The web engine runs on one
 * thread (a Web Worker), so the lock only counts; BRouterEngine uses it to serialise route requests on the phone.
 */
public class TReentrantLock {
    private int holds;

    public void lock() { holds++; }

    public boolean tryLock() { holds++; return true; }

    public boolean tryLock(long timeout, TimeUnit unit) { holds++; return true; }

    public void unlock() {
        if (holds == 0) throw new IllegalMonitorStateException();
        holds--;
    }

    public boolean isLocked() { return holds > 0; }

    public boolean isHeldByCurrentThread() { return holds > 0; }
}
