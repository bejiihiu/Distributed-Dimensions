package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Level C: "plugin present &mdash; join implies data applied" for blocking-login
 * sync plugins that expose no completion event and need no wait
 * (MC Data Bridge, InvSYNC, DataSynchronization with kick-on-failed-sync).
 *
 * <p><b>Semantics (not a confirmation):</b> these plugins gate the login itself on
 * the data being applied — the player is either refused entry or admitted with the
 * snapshot in place. What remains is a conservative fixed delay
 * ({@code sync.wait-ticks}, capped at {@code sync.wait-timeout}) after the join
 * before the arrival flow proceeds. Nothing here is proof of applied data; it is a
 * documented absorption window for the async apply that still trails the join
 * event. Waits bound to this strategy are reported with
 * {@link SyncCause#FIXED_DELAY} and are <em>not</em> degraded (a known sync plugin
 * was detected), but they are also never as strong as an event-confirmed one.
 */
public final class PresenceDelayedIntegration extends AbstractArmedIntegration {

    private final String detectedPlugin;
    private final long waitTicks;

    public PresenceDelayedIntegration(Schedulers schedulers, Predicate<UUID> onlineCheck,
            long waitTicks, long waitTimeoutMillis, LongSupplier clock,
            String detectedPlugin, Logger logger) {
        super(schedulers, onlineCheck, waitTimeoutMillis, waitTicks, clock, logger,
                SyncReadiness.READY, Mode.DELAY_BOUND, SyncCause.FIXED_DELAY, true);
        this.waitTicks = waitTicks;
        this.detectedPlugin = detectedPlugin == null || detectedPlugin.isBlank()
                ? "unknown" : detectedPlugin;
    }

    @Override
    public String id() {
        return "presence-delay";
    }

    @Override
    public String describe() {
        return "join implies ready: " + detectedPlugin + " detected; fixed "
                + waitTicks + "-tick delay (no completion event exists)";
    }

    /** The plugin the integration was bound against — for diagnostics. */
    public String detectedPlugin() {
        return detectedPlugin;
    }

    /** Configured fixed delay in ticks. */
    public long waitTicks() {
        return waitTicks;
    }
}
