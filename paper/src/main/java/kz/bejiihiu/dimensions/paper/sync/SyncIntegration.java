package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import java.util.Objects;
import java.util.UUID;

/**
 * Contract between the transfer flow and the external player-data sync plugin.
 * The plugin never syncs player data itself — it only observes (or conservatively
 * assumes) the external plugin's completion, so arrival can proceed safely.
 *
 * <p>Implementations must never block and must never join a future on the game
 * thread. Every wait completes exactly one {@link SyncCallback} method, at most
 * once, and is bounded by {@code sync.wait-timeout}.
 */
public interface SyncIntegration {

    /** Stable identifier, e.g. {@code husksync-api}, {@code reflection}, {@code presence-delay}. */
    String id();

    /** Human-readable description for logs and diagnostics. */
    String describe();

    /** Current readiness posture; never transitions back to {@link SyncReadiness#READY} once failed. */
    SyncReadiness phase();

    /** True while the integration is armed and usable. */
    boolean isAvailable();

    /**
     * Arm a wait for the player's data to be applied on this backend. Returns
     * immediately; the callback fires when the wait completes or fails.
     */
    void waitForSync(UUID player, SyncCallback callback);

    /** Release listeners and cancel pending waits (pending callbacks get {@link TimeoutReason#INTEGRATION_ERROR}). */
    default void shutdown() {
    }

    /**
     * Absent-plugin integration: never reports readiness immediately, so a wait
     * rides out its full configured timeout and the caller handles the failure.
     */
    class NoOp implements SyncIntegration {

        private final Schedulers schedulers;
        private final long waitTimeoutMillis;

        NoOp(Schedulers schedulers, long waitTimeoutMillis) {
            this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
            this.waitTimeoutMillis = waitTimeoutMillis;
        }

        @Override
        public String id() {
            return "noop";
        }

        @Override
        public String describe() {
            return "no sync integration — every wait times out after " + waitTimeoutMillis + " ms";
        }

        @Override
        public SyncReadiness phase() {
            return SyncReadiness.FAILED;
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void waitForSync(UUID player, SyncCallback callback) {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(callback, "callback");
            schedulers.runGlobalDelayed(() -> fireTimeout(callback), SyncTicks.ticksForMillis(waitTimeoutMillis));
        }

        /** Hook for subclasses to observe the timeout firing. */
        protected void fireTimeout(SyncCallback callback) {
            callback.onTimeout(TimeoutReason.WAIT_TIMEOUT);
        }
    }

    /**
     * No-op semantics with loud, actionable logging: a SEVERE at creation naming the
     * fix, and a WARNING per timed-out wait. Selected when a configured integration
     * cannot be armed (never when {@code auto} finds nothing — that is the degraded
     * fallback's job).
     */
    final class LoggingFallback extends NoOp {

        private final org.slf4j.Logger logger;
        private final String reason;

        LoggingFallback(String reason, Schedulers schedulers, long waitTimeoutMillis,
                org.slf4j.Logger logger) {
            super(schedulers, waitTimeoutMillis);
            this.reason = reason;
            this.logger = Objects.requireNonNull(logger, "logger");
            logger.error("SEVERE: {} — player data will NOT be confirmed as applied. "
                    + "Install HuskSync (https://william278.net/project/husksync) or set "
                    + "sync.plugin=reflection with sync.reflection.event-class in config.yml; "
                    + "transfers will fail after the sync wait timeout until then.", reason);
        }

        @Override
        public String id() {
            return "logging-fallback";
        }

        @Override
        public String describe() {
            return "confirmed-sync unavailable: " + reason;
        }

        @Override
        protected void fireTimeout(SyncCallback callback) {
            logger.warn("sync: wait timed out ({}); transfers are failing — install HuskSync or fix sync.plugin", reason);
            callback.onTimeout(TimeoutReason.WAIT_TIMEOUT);
        }
    }
}
