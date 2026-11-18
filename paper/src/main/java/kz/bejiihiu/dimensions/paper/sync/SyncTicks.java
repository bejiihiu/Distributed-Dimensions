package kz.bejiihiu.dimensions.paper.sync;

/** Millisecond → tick helpers shared by the integration implementations. */
final class SyncTicks {

    private SyncTicks() {
    }

    /** Whole ticks needed to wait at least {@code millis}; always at least one tick. */
    static long ticksForMillis(long millis) {
        if (millis <= 0) {
            return 1;
        }
        return (millis + 49) / 50;
    }
}
