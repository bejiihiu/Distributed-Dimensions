package kz.bejiihiu.dimensions.mirage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Minimal counters for the mirage feature and its guards. Diagnostics only —
 * structured logging at debug level and, eventually, admin commands; no external
 * metrics service is involved.
 */
public final class MirageMetrics {

    private final LongAdder teleportsCancelled = new LongAdder();
    private final LongAdder entityTeleportsCancelled = new LongAdder();
    private final LongAdder respawnRedirects = new LongAdder();
    private final LongAdder unloadsCancelled = new LongAdder();
    private final LongAdder blockEditsCancelled = new LongAdder();

    /** Simple per-player rate limiter for the block-edit warning (5s window). */
    private final ConcurrentHashMap<java.util.UUID, Long> lastBlockEditWarning = new ConcurrentHashMap<>();
    private static final long BLOCK_EDIT_WARNING_WINDOW_MILLIS = 5_000L;

    public void recordTeleportCancelled() {
        teleportsCancelled.increment();
    }

    public void recordEntityTeleportCancelled() {
        entityTeleportsCancelled.increment();
    }

    public void recordRespawnRedirect() {
        respawnRedirects.increment();
    }

    public void recordUnloadCancelled() {
        unloadsCancelled.increment();
    }

    public void recordBlockEditCancelled() {
        blockEditsCancelled.increment();
    }

    public long teleportsCancelled() {
        return teleportsCancelled.sum();
    }

    public long entityTeleportsCancelled() {
        return entityTeleportsCancelled.sum();
    }

    public long respawnRedirects() {
        return respawnRedirects.sum();
    }

    public long unloadsCancelled() {
        return unloadsCancelled.sum();
    }

    public long blockEditsCancelled() {
        return blockEditsCancelled.sum();
    }

    /** @return true when this player may be shown the warning now (and the window is reset) */
    public boolean tryAcquireBlockEditWarning(java.util.UUID playerId, long nowMillis) {
        Long last = lastBlockEditWarning.get(playerId);
        if (last != null && nowMillis - last < BLOCK_EDIT_WARNING_WINDOW_MILLIS) {
            return false;
        }
        lastBlockEditWarning.put(playerId, nowMillis);
        return true;
    }
}
