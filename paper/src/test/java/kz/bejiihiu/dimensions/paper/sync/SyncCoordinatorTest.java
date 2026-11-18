package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.common.config.SyncSettings;
import kz.bejiihiu.dimensions.paper.TestPlayers;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SyncCoordinatorTest {

    private static final Predicate<UUID> ALWAYS_ONLINE = uuid -> true;
    private static final Predicate<UUID> ALWAYS_OFFLINE = uuid -> false;

    private final FakeSchedulers schedulers = new FakeSchedulers();

    private static SyncSettings settings(boolean enabled, boolean waitForSync) {
        return new SyncSettings(enabled, waitForSync, "auto",
                Duration.ofMillis(5_000), 20, "", "getPlayer");
    }

    private SyncCoordinator coordinatorWith(SyncIntegration integration, SyncSettings settings) {
        return new SyncCoordinator(integration, settings, LoggerFactory.getLogger(getClass()));
    }

    @Test
    void awaitSyncCompletesWithAReportWhenTheWaitSucceeds() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = TestPlayers.create(uuid, true);
        SyncIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "MC Data Bridge", LoggerFactory.getLogger(getClass()));
        SyncCoordinator coordinator = coordinatorWith(integration, settings(true, true));

        java.util.concurrent.CompletableFuture<SyncReport> future = coordinator.awaitSync(player);
        assertFalse(future.isDone(), "wait must not complete before the delay elapses");

        schedulers.advanceTicks(20);

        SyncReport report = future.get(1, TimeUnit.SECONDS);
        assertEquals("presence-delay", report.strategy());
        assertFalse(report.degraded(), "a known sync plugin means the report is not degraded");
        assertTrue(report.durationMillis() >= 0);
    }

    @Test
    void awaitSyncSurfacesTimeoutAsExceptionalCompletion() {
        UUID uuid = UUID.randomUUID();
        Player player = TestPlayers.create(uuid, false);
        SyncIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_OFFLINE, 20, 5_000, schedulers::currentMillis,
                "no sync plugin detected", LoggerFactory.getLogger(getClass()));
        SyncCoordinator coordinator = coordinatorWith(integration, settings(true, true));

        java.util.concurrent.CompletableFuture<SyncReport> future = coordinator.awaitSync(player);
        schedulers.advanceTicks(20);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS));
        assertTrue(thrown.getCause() instanceof SyncTimeoutException);
        SyncTimeoutException cause = (SyncTimeoutException) thrown.getCause();
        assertEquals(uuid, cause.player());
        assertEquals(TimeoutReason.PLAYER_OFFLINE, cause.reason());
        assertEquals(SyncReadiness.FAILED, integration.phase());
    }

    @Test
    void waitForSyncDisabledShortCircuitsWithADegradedReport() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = TestPlayers.create(uuid, true);
        SyncIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "no sync plugin detected", LoggerFactory.getLogger(getClass()));
        SyncCoordinator coordinator = coordinatorWith(integration, settings(true, false));

        SyncReport report = coordinator.awaitSync(player).get(1, TimeUnit.SECONDS);

        assertEquals("disabled", report.strategy());
        assertTrue(report.degraded());
        assertEquals(0, schedulers.pendingTasks(), "disabled wait must not arm anything");
    }

    @Test
    void syncIntegrationDisabledShortCircuitsWithADegradedReport() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = TestPlayers.create(uuid, true);
        SyncIntegration integration = new PresenceDelayedIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "MC Data Bridge", LoggerFactory.getLogger(getClass()));
        SyncCoordinator coordinator = coordinatorWith(integration, settings(false, true));

        SyncReport report = coordinator.awaitSync(player).get(1, TimeUnit.SECONDS);

        assertEquals("disabled", report.strategy());
        assertTrue(report.degraded());
        assertEquals(0, schedulers.pendingTasks());
    }

    @Test
    void degradedStrategyIsFlaggedInTheReport() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = TestPlayers.create(uuid, true);
        SyncIntegration integration = new DegradedFallbackIntegration(
                schedulers, ALWAYS_ONLINE, 20, 5_000, schedulers::currentMillis,
                "none installed", LoggerFactory.getLogger(getClass()));
        SyncCoordinator coordinator = coordinatorWith(integration, settings(true, true));

        java.util.concurrent.CompletableFuture<SyncReport> future = coordinator.awaitSync(player);
        schedulers.advanceTicks(20);

        SyncReport report = future.get(1, TimeUnit.SECONDS);
        assertEquals("degraded-fallback", report.strategy());
        assertTrue(report.degraded());
    }
}
