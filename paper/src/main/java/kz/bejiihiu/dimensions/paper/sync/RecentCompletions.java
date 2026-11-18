package kz.bejiihiu.dimensions.paper.sync;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived memory of recent sync completions, so a wait armed <em>after</em> the
 * completion event fired (the event can beat the arrival flow's
 * {@code awaitSync} call) still completes immediately instead of hanging until the
 * timeout. Entries expire after the configured wait timeout and the map is bounded.
 */
final class RecentCompletions {

    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentHashMap<UUID, Long> seen = new ConcurrentHashMap<>();

    RecentCompletions(long ttlMillis, int maxEntries) {
        this.ttlMillis = ttlMillis;
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** True when a completion for {@code uuid} was recorded within the TTL; consumes the entry. */
    boolean took(UUID uuid, long nowMillis) {
        Long at = seen.remove(uuid);
        return at != null && nowMillis - at <= ttlMillis;
    }

    void offer(UUID uuid, long nowMillis) {
        if (seen.size() >= maxEntries) {
            prune(nowMillis);
        }
        if (seen.size() >= maxEntries) {
            // still full: evict an arbitrary entry rather than grow unboundedly
            Iterator<UUID> it = seen.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        seen.put(uuid, nowMillis);
    }

    private void prune(long nowMillis) {
        for (Iterator<Map.Entry<UUID, Long>> it = seen.entrySet().iterator(); it.hasNext();) {
            if (nowMillis - it.next().getValue() > ttlMillis) {
                it.remove();
            }
        }
    }
}
