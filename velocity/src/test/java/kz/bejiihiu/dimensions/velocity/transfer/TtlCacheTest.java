package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.velocity.support.AdjustableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TtlCacheTest {

    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-18T12:00:00Z"));

    @Test
    void returnsLiveEntryUntilTtlElapses() {
        TtlCache<String, UUID> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        UUID id = UUID.randomUUID();
        cache.put("k", id);
        assertEquals(id, cache.get("k"));
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertEquals(id, cache.get("k"));
        clock.advance(Duration.ofSeconds(1));
        assertNull(cache.get("k"), "entry must expire once the TTL elapses");
    }

    @Test
    void putIfAbsentReturnsExistingWhileLive() {
        TtlCache<String, UUID> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertNull(cache.putIfAbsent("k", first));
        assertEquals(first, cache.putIfAbsent("k", second));
        assertEquals(1, cache.size());
    }

    @Test
    void putIfAbsentReplacesExpiredEntry() {
        TtlCache<String, UUID> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        cache.put("k", UUID.randomUUID());
        clock.advance(Duration.ofMinutes(11));
        UUID replacement = UUID.randomUUID();
        assertNull(cache.putIfAbsent("k", replacement));
        assertEquals(replacement, cache.get("k"));
    }

    @Test
    void evictsOldestLiveEntryOnOverflow() {
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 2, clock);
        cache.put("a", "a");
        clock.advance(Duration.ofSeconds(1));
        cache.put("b", "b");
        clock.advance(Duration.ofSeconds(1));
        cache.put("c", "c"); // a must be evicted, b and c stay
        assertNull(cache.get("a"));
        assertEquals("b", cache.get("b"));
        assertEquals("c", cache.get("c"));
    }

    @Test
    void sizeExcludesExpiredEntries() {
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        cache.put("a", "a");
        cache.put("b", "b");
        clock.advance(Duration.ofMinutes(11));
        assertEquals(0, cache.size());
    }

    @Test
    void sweepOnPutAvoidsEvictingLiveEntries() {
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 2, clock);
        cache.put("stale", "stale");
        clock.advance(Duration.ofMinutes(11));
        cache.put("fresh-1", "fresh-1");
        clock.advance(Duration.ofSeconds(1));
        cache.put("fresh-2", "fresh-2"); // fresh-1 must survive; "stale" was already swept
        assertEquals("fresh-1", cache.get("fresh-1"));
        assertEquals("fresh-2", cache.get("fresh-2"));
        assertEquals(2, cache.size());
    }

    @Test
    void drainMatchingValuesRemovesOnlyMatches() {
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        cache.put("a", "keep-a");
        cache.put("b", "drop-b");
        cache.put("c", "drop-c");
        List<String> drained = cache.drainMatchingValues(v -> v.startsWith("drop"));
        assertEquals(2, drained.size());
        assertEquals(List.of("keep-a"), cache.valuesSnapshot());
    }

    @Test
    void removeReturnsLiveValueAndForgets() {
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10).toMillis(), 16, clock);
        cache.put("k", "v");
        assertEquals("v", cache.remove("k"));
        assertNull(cache.remove("k"));
        assertEquals(0, cache.size());
    }
}
