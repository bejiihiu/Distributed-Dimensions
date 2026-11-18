package kz.bejiihiu.dimensions.paper.sync;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentCompletionsTest {

    @Test
    void freshCompletionIsConsumedOnce() {
        RecentCompletions cache = new RecentCompletions(5_000, 16);
        UUID uuid = UUID.randomUUID();

        cache.offer(uuid, 1_000);

        assertTrue(cache.took(uuid, 1_100));
        assertFalse(cache.took(uuid, 1_100), "a completion feeds exactly one wait");
    }

    @Test
    void staleCompletionIsIgnored() {
        RecentCompletions cache = new RecentCompletions(5_000, 16);
        UUID uuid = UUID.randomUUID();

        cache.offer(uuid, 1_000);

        assertFalse(cache.took(uuid, 6_001), "entries older than the TTL are stale");
    }

    @Test
    void completionExactlyAtTtlBoundaryStillCounts() {
        RecentCompletions cache = new RecentCompletions(5_000, 16);
        UUID uuid = UUID.randomUUID();

        cache.offer(uuid, 1_000);

        assertTrue(cache.took(uuid, 6_000));
    }

    @Test
    void fullMapPrunesStaleEntriesBeforeAcceptingNew() {
        RecentCompletions cache = new RecentCompletions(5_000, 8);
        long now = 10_000;
        List<UUID> stale = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID uuid = UUID.randomUUID();
            stale.add(uuid);
            cache.offer(uuid, 1_000); // 9 s old — past the TTL
        }

        UUID fresh = UUID.randomUUID();
        cache.offer(fresh, now); // map is full; the stale ones must be pruned to make room

        assertTrue(cache.took(fresh, now));
    }
}
