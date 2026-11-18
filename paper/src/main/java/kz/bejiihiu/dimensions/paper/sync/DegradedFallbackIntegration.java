package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Level D: the degraded floor for when no usable sync plugin is installed (or
 * explicitly disabled via {@code sync.plugin=none}). The transfer is not blocked —
 * that would brick the network — but the data is never confirmed:
 *
 * <ul>
 *   <li>a loud warning is emitted once at enable (startup) with the exact fix;</li>
 *   <li>waits complete after the fixed {@code sync.wait-ticks} delay and are
 *       flagged degraded (see {@link SyncReport#degraded()});</li>
 *   <li>a wait that cannot proceed (player gone) transitions the strategy to
 *       {@link SyncReadiness#FAILED} loudly — never a silent dead-end;</li>
 *   <li>the wait reports {@link SyncCause#DEGRADED}, not a confirmation.</li>
 * </ul>
 */
public final class DegradedFallbackIntegration extends AbstractArmedIntegration {

    public DegradedFallbackIntegration(Schedulers schedulers, Predicate<UUID> onlineCheck,
            long waitTicks, long waitTimeoutMillis, LongSupplier clock,
            String reason, Logger logger) {
        super(schedulers, onlineCheck, waitTimeoutMillis, waitTicks, clock, logger,
                SyncReadiness.DEGRADED, Mode.DELAY_BOUND, SyncCause.DEGRADED, false);
        logger.warn("sync: DEGRADED — {}. Transfers will proceed after a fixed {}-tick delay "
                        + "WITHOUT confirmation that the player's data was applied. Install HuskSync "
                        + "(https://william278.net/project/husksync) on every backend, or set "
                        + "sync.plugin=reflection with sync.reflection.event-class. See docs/player-sync.md.",
                reason, waitTicks);
    }

    @Override
    public String id() {
        return "degraded-fallback";
    }

    @Override
    public String describe() {
        return "no sync confirmation available; fixed delay with degraded reporting";
    }

    @Override
    protected void onWaitExpired(UUID player, TimeoutReason reason, boolean online) {
        setPhase(SyncReadiness.FAILED);
        logger().error("SEVERE: sync: wait for player {} failed ({}). Player data could NOT be "
                        + "confirmed as applied. Install HuskSync or set sync.plugin=reflection "
                        + "(or sync.plugin=delayed when the sync plugin gates login), then restart.",
                player, reason);
    }
}
