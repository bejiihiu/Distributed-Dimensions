package kz.bejiihiu.dimensions.paper.sync;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Records everything the callback fires; never throws, counts invocations. */
final class CaptureCallback implements SyncCallback {

    final AtomicReference<SyncReady> ready = new AtomicReference<>();
    final AtomicReference<TimeoutReason> timeout = new AtomicReference<>();
    final AtomicInteger invocations = new AtomicInteger();

    @Override
    public void onReady(SyncReady ready) {
        invocations.incrementAndGet();
        this.ready.set(ready);
    }

    @Override
    public void onTimeout(TimeoutReason reason) {
        invocations.incrementAndGet();
        this.timeout.set(reason);
    }

    boolean firedOnce() {
        return invocations.get() == 1;
    }
}
