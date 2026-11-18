package kz.bejiihiu.dimensions.paper.chunktickets;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pure bookkeeping for plugin chunk tickets: which chunks we hold and when each
 * ticket must expire. No platform types — the expiry math is unit-tested directly.
 *
 * <p>Tickets are not owned by the platform with a TTL (plugin tickets live until
 * removed), so this ledger reproduces the vanilla "destination chunk stays loaded
 * for 15 s, refreshed on every transfer" behavior for portal-arrival chunks.
 *
 * <p>All methods are synchronized; calls come from region threads of several chunks,
 * and the critical section is a hash-map operation — no blocking, no world access.
 */
final class TicketLedger {

    /** Default time-to-live: 300 ticks = 15 seconds, matching the vanilla portal behavior. */
    static final long DEFAULT_TTL_TICKS = 300;

    private final long ttlTicks;
    private final Map<TicketKey, Long> expiries = new HashMap<>();

    TicketLedger() {
        this(DEFAULT_TTL_TICKS);
    }

    TicketLedger(long ttlTicks) {
        if (ttlTicks <= 0) {
            throw new IllegalArgumentException("ttlTicks must be > 0, got " + ttlTicks);
        }
        this.ttlTicks = ttlTicks;
    }

    /**
     * Record a (re)touch of the chunk. Creates the entry on first touch, otherwise
     * extends the expiry. Returns the outcome for the caller to act on.
     */
    synchronized Touch touch(TicketKey key, long nowTick) {
        boolean created = !expiries.containsKey(key);
        long expiresAt = nowTick + ttlTicks;
        expiries.put(key, expiresAt);
        return new Touch(created, expiresAt);
    }

    /** True when the key is tracked and its expiry has passed as of {@code nowTick}. */
    synchronized boolean isDue(TicketKey key, long nowTick) {
        Long expiresAt = expiries.get(key);
        return expiresAt != null && nowTick >= expiresAt;
    }

    /** If the key is due, drop it from the ledger and return true. */
    synchronized boolean expireIfDue(TicketKey key, long nowTick) {
        if (isDue(key, nowTick)) {
            expiries.remove(key);
            return true;
        }
        return false;
    }

    /** Drop the key entirely (used when a ticket is explicitly removed). */
    synchronized boolean forget(TicketKey key) {
        return expiries.remove(key) != null;
    }

    /** Number of tracked chunks. */
    synchronized int size() {
        return expiries.size();
    }

    /** Immutable key set snapshot for shutdown-time cleanup. */
    synchronized Set<TicketKey> keys() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(expiries.keySet()));
    }

    /** Outcome of a touch. */
    record Touch(boolean created, long expiresAtTick) {
    }
}
