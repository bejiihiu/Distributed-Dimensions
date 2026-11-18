package kz.bejiihiu.dimensions.paper.chunktickets;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure TTL/refresh bookkeeping for plugin chunk tickets. */
class TicketLedgerTest {

    private static TicketKey key(int x, int z) {
        return new TicketKey("world-a", x, z);
    }

    @Test
    void firstTouchCreatesWithExpiryAtNowPlusTtl() {
        TicketLedger ledger = new TicketLedger();
        TicketLedger.Touch touch = ledger.touch(key(3, -2), 100);
        assertTrue(touch.created());
        assertEquals(100 + TicketLedger.DEFAULT_TTL_TICKS, touch.expiresAtTick());
        assertEquals(1, ledger.size());
    }

    @Test
    void secondTouchRefreshesInsteadOfCreating() {
        TicketLedger ledger = new TicketLedger();
        ledger.touch(key(3, -2), 0);
        TicketLedger.Touch touch = ledger.touch(key(3, -2), 250);
        assertFalse(touch.created());
        assertEquals(250 + TicketLedger.DEFAULT_TTL_TICKS, touch.expiresAtTick());
        assertEquals(1, ledger.size());
    }

    @Test
    void dueExactlyAtExpiryBoundary() {
        TicketLedger ledger = new TicketLedger();
        ledger.touch(key(0, 0), 1000);
        assertFalse(ledger.isDue(key(0, 0), 1000 + TicketLedger.DEFAULT_TTL_TICKS - 1));
        assertTrue(ledger.isDue(key(0, 0), 1000 + TicketLedger.DEFAULT_TTL_TICKS));
    }

    @Test
    void expireIfDueDropsTheEntryExactlyOnce() {
        TicketLedger ledger = new TicketLedger();
        ledger.touch(key(0, 0), 0);
        assertFalse(ledger.expireIfDue(key(0, 0), TicketLedger.DEFAULT_TTL_TICKS - 1));
        assertTrue(ledger.expireIfDue(key(0, 0), TicketLedger.DEFAULT_TTL_TICKS));
        assertFalse(ledger.expireIfDue(key(0, 0), TicketLedger.DEFAULT_TTL_TICKS + 1000));
        assertEquals(0, ledger.size());
    }

    @Test
    void forgottenKeyIsNoLongerDueAndAbsent() {
        TicketLedger ledger = new TicketLedger();
        ledger.touch(key(5, 5), 0);
        assertTrue(ledger.forget(key(5, 5)));
        assertFalse(ledger.expireIfDue(key(5, 5), Long.MAX_VALUE));
        assertEquals(0, ledger.size());
        assertFalse(ledger.forget(key(5, 5)));
    }

    @Test
    void keysSnapshotIsImmutableAndReflectsState() {
        TicketLedger ledger = new TicketLedger();
        ledger.touch(key(1, 1), 0);
        ledger.touch(key(2, 2), 0);
        Set<TicketKey> keys = ledger.keys();
        assertEquals(2, keys.size());
        assertThrows(UnsupportedOperationException.class, () -> keys.add(key(9, 9)));
        ledger.forget(key(1, 1));
        assertEquals(2, keys.size(), "snapshot must not observe later removals");
    }

    @Test
    void ringAroundProducesEightDistinctNeighbours() {
        List<TicketKey> ring = TicketKey.neighbors(new TicketKey("w", 10, 20));
        assertEquals(8, ring.size());
        assertEquals(8, new HashSet<>(ring).size());
        Set<TicketKey> expected = Set.of(
                new TicketKey("w", 9, 19), new TicketKey("w", 10, 19), new TicketKey("w", 11, 19),
                new TicketKey("w", 9, 20), new TicketKey("w", 11, 20),
                new TicketKey("w", 9, 21), new TicketKey("w", 10, 21), new TicketKey("w", 11, 21));
        assertEquals(expected, new HashSet<>(ring));
        assertFalse(ring.contains(new TicketKey("w", 10, 20)), "center must not be in its own ring");
    }

    @Test
    void nonPositiveTtlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TicketLedger(0));
        assertThrows(IllegalArgumentException.class, () -> new TicketLedger(-1));
    }
}
