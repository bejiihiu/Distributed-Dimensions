package kz.bejiihiu.dimensions.velocity.transfer;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Small bounded TTL map with lazy expiry, used for the transfer idempotency caches.
 *
 * <p>Semantics:
 * <ul>
 *   <li>entries expire {@link #ttlMillis} after insertion (or last replacement);</li>
 *   <li>{@link #maxSize} caps the live entry count - on overflow the oldest live entry is
 *       evicted, so memory stays flat on a long-running proxy;</li>
 *   <li>expired entries are never returned by {@link #get}/{@link #putIfAbsent} and are
 *       swept on every write, so the map cannot grow stale debt unboundedly.</li>
 * </ul>
 *
 * All methods are synchronized; transfer operations are rare (a handful per second at most),
 * so a guarded map beats a lock-free structure with weaker semantics here.
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAtMillis, long createdAtMillis) {
    }

    private final long ttlMillis;
    private final int maxSize;
    private final Clock clock;
    private final java.util.LinkedHashMap<K, Entry<V>> map = new java.util.LinkedHashMap<>();

    public TtlCache(long ttlMillis, int maxSize, Clock clock) {
        if (ttlMillis <= 0 || maxSize <= 0) {
            throw new IllegalArgumentException("ttl and maxSize must be positive");
        }
        this.ttlMillis = ttlMillis;
        this.maxSize = maxSize;
        this.clock = clock;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    public int maxSize() {
        return maxSize;
    }

    /** Inserts or replaces an entry. Never returns an expired entry. */
    public synchronized V put(K key, V value) {
        sweepExpired();
        Entry<V> entry = new Entry<>(value, clock.millis() + ttlMillis, clock.millis());
        Entry<V> previous = map.put(key, entry);
        evictOverflow();
        return previous == null || isExpired(previous) ? null : previous.value();
    }

    /** Returns the live entry for {@code key}, or null if absent or expired. */
    public synchronized V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            map.remove(key);
            return null;
        }
        return entry.value();
    }

    /**
     * Atomically returns the live existing value for {@code key}, or inserts {@code value}
     * and returns null. Expired entries are replaced.
     */
    public synchronized V putIfAbsent(K key, V value) {
        Entry<V> existing = map.get(key);
        if (existing != null && !isExpired(existing)) {
            return existing.value();
        }
        map.put(key, new Entry<>(value, clock.millis() + ttlMillis, clock.millis()));
        evictOverflow();
        return null;
    }

    public synchronized V remove(K key) {
        Entry<V> entry = map.remove(key);
        return entry == null || isExpired(entry) ? null : entry.value();
    }

    /** Number of live (non-expired) entries. */
    public synchronized int size() {
        sweepExpired();
        return map.size();
    }

    /** Snapshot of the live values, oldest first. Intended for status reporting. */
    public synchronized List<V> valuesSnapshot() {
        sweepExpired();
        List<V> out = new ArrayList<>(map.size());
        for (Entry<V> entry : map.values()) {
            out.add(entry.value());
        }
        return out;
    }

    /** Removes every live entry matching {@code valuePredicate} and returns the removed values. */
    public synchronized List<V> drainMatchingValues(Predicate<V> valuePredicate) {
        List<V> drained = new ArrayList<>();
        var it = map.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (isExpired(e.getValue())) {
                it.remove();
                continue;
            }
            if (valuePredicate.test(e.getValue().value())) {
                drained.add(e.getValue().value());
                it.remove();
            }
        }
        return drained;
    }

    private void sweepExpired() {
        long now = clock.millis();
        map.values().removeIf(e -> now >= e.expiresAtMillis());
    }

    private void evictOverflow() {
        sweepExpired();
        if (map.size() <= maxSize) {
            return;
        }
        Optional<K> eldest = map.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().createdAtMillis()))
                .map(java.util.Map.Entry::getKey);
        eldest.ifPresent(map::remove);
    }

    private boolean isExpired(Entry<V> entry) {
        return clock.millis() >= entry.expiresAtMillis();
    }
}
