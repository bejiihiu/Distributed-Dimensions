package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.common.config.SyncSettings;

import org.bukkit.entity.Player;

import org.slf4j.Logger;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link SyncOperations} implementation: bridges one {@code awaitSync} call to
 * the armed {@link SyncIntegration} and reports the outcome exactly once.
 *
 * <p>Honours {@code sync.enabled} / {@code sync.wait-for-sync}: when either is off,
 * no integration is consulted and the future completes immediately with a degraded
 * {@code disabled} report (transfers proceed; a one-time warning is logged).
 */
public final class SyncCoordinator implements SyncOperations {

    private final SyncIntegration integration;
    private final SyncSettings settings;
    private final Logger logger;
    private final AtomicBoolean disabledWarned = new AtomicBoolean();

    public SyncCoordinator(SyncIntegration integration, SyncSettings settings, Logger logger) {
        this.integration = Objects.requireNonNull(integration, "integration");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public CompletableFuture<SyncReport> awaitSync(Player player) {
        Objects.requireNonNull(player, "player");
        if (!settings.enabled() || !settings.waitForSync()) {
            if (disabledWarned.compareAndSet(false, true)) {
                logger.warn("sync: wait-for-sync is disabled ({}); transfers proceed with NO confirmation "
                                + "that player data has been applied. Do not use this in production.",
                        settings.enabled() ? "sync.wait-for-sync=false" : "sync.enabled=false");
            }
            return CompletableFuture.completedFuture(new SyncReport("disabled", 0, true));
        }

        UUID uuid = player.getUniqueId();
        long startNanos = System.nanoTime();
        CompletableFuture<SyncReport> future = new CompletableFuture<>();
        integration.waitForSync(uuid, new SyncCallback() {
            @Override
            public void onReady(SyncReady ready) {
                long elapsed = millisSince(startNanos);
                boolean degraded = integration.phase() != SyncReadiness.READY;
                if (degraded) {
                    logger.info("sync: player {} proceeded unconfirmed (strategy={}, took={} ms, degraded)",
                            uuid, integration.id(), elapsed);
                } else {
                    logger.info("sync: player {} data confirmed (strategy={}, cause={}, took={} ms)",
                            uuid, integration.id(), ready.cause(), elapsed);
                }
                future.complete(new SyncReport(integration.id(), elapsed, degraded));
            }

            @Override
            public void onTimeout(TimeoutReason reason) {
                long elapsed = millisSince(startNanos);
                logger.warn("sync: timed out waiting for player {} (strategy={}, reason={}, took={} ms)",
                        uuid, integration.id(), reason, elapsed);
                future.completeExceptionally(new SyncTimeoutException(uuid, reason));
            }
        });
        return future;
    }

    /** The armed integration — for discovery logs and diagnostics. Never shut down here. */
    public SyncIntegration integration() {
        return integration;
    }

    private static long millisSince(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
