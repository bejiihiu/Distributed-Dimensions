package kz.bejiihiu.dimensions.mirage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirageMetricsTest {

    @Test
    void blockEditWarningIsRateLimitedPerPlayer() {
        MirageMetrics metrics = new MirageMetrics();
        UUID player = UUID.randomUUID();
        assertTrue(metrics.tryAcquireBlockEditWarning(player, 10_000L));
        assertFalse(metrics.tryAcquireBlockEditWarning(player, 12_000L));
        assertFalse(metrics.tryAcquireBlockEditWarning(player, 14_999L));
        assertTrue(metrics.tryAcquireBlockEditWarning(player, 15_000L));
    }

    @Test
    void differentPlayersHaveIndependentWindows() {
        MirageMetrics metrics = new MirageMetrics();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(metrics.tryAcquireBlockEditWarning(first, 1_000L));
        assertTrue(metrics.tryAcquireBlockEditWarning(second, 1_000L));
        assertFalse(metrics.tryAcquireBlockEditWarning(first, 2_000L));
    }

    @Test
    void countersIncrementIndependently() {
        MirageMetrics metrics = new MirageMetrics();
        metrics.recordTeleportCancelled();
        metrics.recordTeleportCancelled();
        metrics.recordEntityTeleportCancelled();
        metrics.recordRespawnRedirect();
        metrics.recordUnloadCancelled();
        metrics.recordBlockEditCancelled();
        assertEquals(2L, metrics.teleportsCancelled());
        assertEquals(1L, metrics.entityTeleportsCancelled());
        assertEquals(1L, metrics.respawnRedirects());
        assertEquals(1L, metrics.unloadsCancelled());
        assertEquals(1L, metrics.blockEditsCancelled());
    }
}
