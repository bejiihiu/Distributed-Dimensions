package kz.bejiihiu.dimensions.paper.transfer;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded, TTL-capped map from operation id to the tracked {@link TransferPending}.
 * Thread-safe. Entries expire lazily on access and are proactively swept by the watchdog;
 * the size cap evicts the oldest entries when reached.
 */
final class TransferRouteTracker {

    private static final int MAX_ENTRIES = 1024;

    private final ConcurrentHashMap<UUID, TransferPending> entries = new ConcurrentHashMap<>();

    void track(TransferPending pending) {
        if (entries.size() >= MAX_ENTRIES) {
            evictOldest(Instant.now());
        }
        entries.putIfAbsent(pending.operationId(), pending);
    }

    /** Looks up without removing; expired entries are dropped and reported as absent. */
    Optional<TransferPending> peek(UUID operationId, Instant at) {
        TransferPending pending = entries.get(operationId);
        if (pending == null) {
            return Optional.empty();
        }
        if (pending.isExpired(at)) {
            entries.remove(operationId, pending);
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    void drop(UUID operationId) {
        entries.remove(operationId);
    }

    /** Test/diagnostic view. */
    int size() {
        return entries.size();
    }

    private void evictOldest(Instant now) {
        // drop the earliest-expiring entry; called only at the cap boundary
        entries.values().stream()
                .min(java.util.Comparator.comparing(TransferPending::expiresAt))
                .map(TransferPending::operationId)
                .ifPresent(entries::remove);
    }
}
