package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelayBoundIntegrationsTest {

    private static final Predicate<UUID> ALWAYS_ONLINE = uuid -> true;
    private static final Predicate<UUID> ALWAYS_OFFLINE = uuid -> false;

    private final FakeSchedulers schedulers = new FakeSchedulers();

    // ------------------------------------------------------------------ level C

    @Test
    void presenceDelayCompletesAfterTheConfiguredTicks() {
        UUID uuid = UUID.randomUUID();
        PresenceDelayedIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "MC Data Bridge", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        schedulers.advanceTicks(19);
        assertFalse(callback.firedOnce(), "must not complete before the fixed delay");
        schedulers.advanceTicks(1);

        assertTrue(callback.firedOnce());
        assertEquals(SyncCause.FIXED_DELAY, callback.ready.get().cause());
        assertEquals(uuid, callback.ready.get().player());
        assertEquals(SyncReadiness.READY, integration.phase());
        assertEquals("presence-delay", integration.id());
        assertTrue(integration.isAvailable());
    }

    @Test
    void presenceDelayReportsOfflinePlayersAsPlayerOffline() {
        UUID uuid = UUID.randomUUID();
        PresenceDelayedIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_OFFLINE, 20, 5_000, schedulers::currentMillis,
                "InvSYNC", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        schedulers.advanceTicks(20);

        assertEquals(TimeoutReason.PLAYER_OFFLINE, callback.timeout.get());
        assertTrue(callback.firedOnce());
        assertEquals(SyncReadiness.READY, integration.phase(),
                "a player leaving is not an integration failure");
    }

    @Test
    void presenceDelayNeverExceedsTheWaitTimeout() {
        UUID uuid = UUID.randomUUID();
        // wait-ticks 100 (5 s) but wait-timeout 250 ms → armed task must land at tick 5
        PresenceDelayedIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_ONLINE, 100, 250, schedulers::currentMillis,
                "MC Data Bridge", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        assertEquals(java.util.List.of("global:5"), schedulers.calls());
        schedulers.advanceTicks(5);
        assertTrue(callback.firedOnce());
        assertEquals(SyncCause.FIXED_DELAY, callback.ready.get().cause());
    }

    // ------------------------------------------------------------------ level D

    @Test
    void degradedFallbackCompletesUnconfirmed() {
        UUID uuid = UUID.randomUUID();
        DegradedFallbackIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "no sync plugin detected", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        schedulers.advanceTicks(20);

        assertTrue(callback.firedOnce());
        assertEquals(SyncCause.DEGRADED, callback.ready.get().cause());
        assertEquals(SyncReadiness.DEGRADED, integration.phase());
        assertFalse(integration.isAvailable(), "degraded fallback reports itself unavailable");
        assertEquals("degraded-fallback", integration.id());
    }

    @Test
    void degradedFallbackTransitionsToFailedLoudlyWhenTheWaitFails() {
        UUID uuid = UUID.randomUUID();
        DegradedFallbackIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_OFFLINE, 20, 5_000, schedulers::currentMillis,
                "no sync plugin detected", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        schedulers.advanceTicks(20);

        assertEquals(TimeoutReason.PLAYER_OFFLINE, callback.timeout.get());
        assertEquals(SyncReadiness.FAILED, integration.phase());
    }

    @Test
    void degradedFallbackNeverExceedsTheWaitTimeout() {
        UUID uuid = UUID.randomUUID();
        DegradedFallbackIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_ONLINE, 100, 250, schedulers::currentMillis,
                "no sync plugin detected", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        assertEquals(java.util.List.of("global:5"), schedulers.calls());
        schedulers.advanceTicks(5);
        assertTrue(callback.firedOnce());
    }

    // ------------------------------------------------------------------ noop

    @Test
    void noOpNeverReadiesAndTimesOut() {
        UUID uuid = UUID.randomUUID();
        SyncIntegration.NoOp noop = new SyncIntegration.NoOp(schedulers, 1_000);
        CaptureCallback callback = new CaptureCallback();

        noop.waitForSync(uuid, callback);
        schedulers.advanceTicks(20); // 1 s = 20 ticks

        assertEquals(TimeoutReason.WAIT_TIMEOUT, callback.timeout.get());
        assertTrue(callback.firedOnce());
        assertFalse(noop.isAvailable());
        assertEquals(SyncReadiness.FAILED, noop.phase());
    }

    @Test
    void shutdownFailsPendingWaitsLoudly() {
        UUID uuid = UUID.randomUUID();
        PresenceDelayedIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "MC Data Bridge", LoggerFactory.getLogger(getClass()));
        CaptureCallback callback = new CaptureCallback();

        integration.waitForSync(uuid, callback);
        integration.shutdown();

        assertEquals(TimeoutReason.INTEGRATION_ERROR, callback.timeout.get());
        assertEquals(SyncReadiness.FAILED, integration.phase());

        // a wait armed after shutdown must fail immediately, not hang
        CaptureCallback late = new CaptureCallback();
        integration.waitForSync(uuid, late);
        assertEquals(TimeoutReason.INTEGRATION_ERROR, late.timeout.get());
    }
}
