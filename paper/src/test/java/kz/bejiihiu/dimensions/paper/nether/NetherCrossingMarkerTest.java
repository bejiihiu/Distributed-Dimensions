package kz.bejiihiu.dimensions.paper.nether;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherCrossingMarkerTest {

    @BeforeEach
    void setUp() {
        NetherCrossingMarker.clear();
    }

    @Test
    void firstHandlingIsAcceptedAndMarked() {
        UUID player = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(player));
        assertTrue(NetherCrossingMarker.isMarked(player));
    }

    @Test
    void duplicateWithinTheDedupeWindowIsSuppressed() {
        UUID player = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(player));
        // immediately after — the dedupe window suppresses the duplicate handling
        assertFalse(NetherCrossingMarker.tryMark(player));
        // but the marker itself stays visible for the mirage guard
        assertTrue(NetherCrossingMarker.isMarked(player));
    }

    @Test
    void markerExpiryDropsFreshness() {
        UUID player = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(player));
        long later = System.nanoTime() + NetherCrossingMarker.TTL_NANOS + 1;
        assertFalse(NetherCrossingMarker.isMarkedAt(player, later));
    }

    @Test
    void freshMarkerIsStillFreshInsideTtl() {
        UUID player = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(player));
        long later = System.nanoTime() + NetherCrossingMarker.TTL_NANOS / 2;
        assertTrue(NetherCrossingMarker.isMarkedAt(player, later));
    }

    @Test
    void reEntryAfterTheDedupeWindowIsAcceptedAgain() throws InterruptedException {
        UUID player = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(player));
        Thread.sleep(300);   // > 250ms dedupe window
        assertTrue(NetherCrossingMarker.tryMark(player));
    }

    @Test
    void playersAreTrackedIndependently() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(NetherCrossingMarker.tryMark(a));
        assertTrue(NetherCrossingMarker.tryMark(b));
        assertTrue(NetherCrossingMarker.isMarked(a));
        assertTrue(NetherCrossingMarker.isMarked(b));
    }
}
