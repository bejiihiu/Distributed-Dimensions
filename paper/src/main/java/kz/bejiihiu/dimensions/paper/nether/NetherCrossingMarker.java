package kz.bejiihiu.dimensions.paper.nether;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Coexistence marker for plugins that run on the arrival of a portal completion. The Nether
 * portal listener marks the player the moment it takes ownership of a
 * {@code PlayerPortalEvent}; the mirage guard consults the same marker and leaves the event
 * alone instead of requesting a second transfer.
 *
 * <p>The marker is also the re-entry dedupe: a completion that arrives within
 * {@link #DEDUPE_NANOS} of the previous one (same dwell, duplicated dispatch) is suppressed.
 * Vanilla re-arms the readiness timer at the full gamerule cadence, so a legitimate re-attempt
 * after a refused transfer is never suppressed by this window.
 *
 * <p>Deliberately process-local and time-bounded — no persistence, no cross-backend state.
 */
public final class NetherCrossingMarker {

    /** 5 ticks — the smallest sane re-entry dedupe window. */
    public static final long DEDUPE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /** A marker lives long enough to cover the proxy round trip and the arrival. */
    public static final long TTL_NANOS = TimeUnit.MILLISECONDS.toNanos(120_000);

    private static final int CAPACITY = 1024;

    private static final ConcurrentHashMap<UUID, Long> LAST_CROSSING_NANOS = new ConcurrentHashMap<>();

    private NetherCrossingMarker() {
    }

    /**
     * Marks a handled crossing. Returns {@code false} when another handling happened within
     * the dedupe window — the caller still cancels the vanilla event but must not start a
     * second transfer.
     */
    public static boolean tryMark(UUID playerUuid) {
        long now = System.nanoTime();
        evictIfNeeded(now);
        boolean[] accepted = {true};
        LAST_CROSSING_NANOS.compute(playerUuid, (uuid, previous) -> {
            if (previous != null && now - previous < DEDUPE_NANOS) {
                accepted[0] = false;
                return previous;
            }
            return now;
        });
        return accepted[0];
    }

    /** True while the marker is fresh for this player (see {@link #TTL_NANOS}). */
    public static boolean isMarked(UUID playerUuid) {
        return isMarkedAt(playerUuid, System.nanoTime());
    }

    /** Same as {@link #isMarked(UUID)} with an explicit clock reading. Test support. */
    static boolean isMarkedAt(UUID playerUuid, long nowNanos) {
        Long previous = LAST_CROSSING_NANOS.get(playerUuid);
        if (previous == null) {
            return false;
        }
        if (nowNanos - previous >= TTL_NANOS) {
            LAST_CROSSING_NANOS.remove(playerUuid, previous);
            return false;
        }
        return true;
    }

    /** Drops every marker. Test support. */
    public static void clear() {
        LAST_CROSSING_NANOS.clear();
    }

    /** Current number of markers (diagnostics/tests). */
    public static int size() {
        return LAST_CROSSING_NANOS.size();
    }

    private static void evictIfNeeded(long now) {
        if (LAST_CROSSING_NANOS.size() < CAPACITY) {
            return;
        }
        LAST_CROSSING_NANOS.entrySet().removeIf(entry -> now - entry.getValue() >= TTL_NANOS);
        if (LAST_CROSSING_NANOS.size() >= CAPACITY) {
            // still full — drop the oldest markers; a wrongly evicted entry only costs an extra
            // mirage-guard pass for one dwell, never state corruption
            var oldest = LAST_CROSSING_NANOS.entrySet().stream()
                    .min(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
            if (oldest != null) {
                LAST_CROSSING_NANOS.remove(oldest);
            }
        }
    }
}
