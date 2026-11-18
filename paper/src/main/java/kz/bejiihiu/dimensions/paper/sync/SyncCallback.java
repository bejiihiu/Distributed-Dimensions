package kz.bejiihiu.dimensions.paper.sync;

/**
 * Callback for one pending wait. Exactly one of the two methods is invoked, exactly
 * once, from whatever thread drives the strategy (region thread, entity thread or an
 * event bridge). Implementations must never block and must tolerate being called
 * after the transfer was abandoned.
 */
public interface SyncCallback {

    /** Player data was applied (or the configured fallback fired). */
    void onReady(SyncReady ready);

    /** The wait failed; the transfer must be treated as unconfirmed/failed. */
    void onTimeout(TimeoutReason reason);
}
