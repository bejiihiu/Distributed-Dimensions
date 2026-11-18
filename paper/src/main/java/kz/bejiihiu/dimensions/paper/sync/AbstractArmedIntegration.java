package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;
import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;

import org.bukkit.event.Listener;

import org.slf4j.Logger;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Shared machinery for the armed strategies: pending-wait bookkeeping, the bounded
 * timeout, the recent-completion cache and shutdown semantics. Creates exactly one
 * callback invocation per wait, always; a wait can never outlive
 * {@code sync.wait-timeout}.
 *
 * <p>Two wait modes share this base:
 * <ul>
 *   <li>{@link Mode#EVENT_BOUND} — a completion event ends the wait early
 *       ({@link #completeIfPending}); the armed task is the timeout and fires
 *       {@link TimeoutReason#WAIT_TIMEOUT} (or {@link TimeoutReason#PLAYER_OFFLINE}).</li>
 *   <li>{@link Mode#DELAY_BOUND} — the armed task <em>is</em> the completion
 *       (fixed delay, capped at the wait timeout): online players get
 *       {@link SyncReady}, offline players get {@link TimeoutReason#PLAYER_OFFLINE}.</li>
 * </ul>
 */
abstract class AbstractArmedIntegration implements SyncIntegration {

    enum Mode {
        EVENT_BOUND,
        DELAY_BOUND
    }

    /** What the delay-bound completion means; stays {@link SyncCause#FIXED_DELAY} or {@link SyncCause#DEGRADED}. */
    private final SyncCause delayCompletionCause;
    private final Mode mode;
    private final Schedulers schedulers;
    private final Predicate<UUID> onlineCheck;
    private final long waitTimeoutMillis;
    private final long armedDelayTicks;
    private final LongSupplier clock;
    private final Logger logger;
    private final boolean available;
    private final Map<UUID, PendingWait> pending = new ConcurrentHashMap<>();
    private final RecentCompletions recent;

    private volatile boolean shutdown;
    private volatile SyncReadiness phase;

    /** Marker listener owned by subclasses that registered one; unregistered on shutdown. */
    volatile Listener listenerBridge;

    AbstractArmedIntegration(Schedulers schedulers, Predicate<UUID> onlineCheck,
            long waitTimeoutMillis, long delayTicks, LongSupplier clock, Logger logger,
            SyncReadiness initialPhase, Mode mode, SyncCause delayCompletionCause,
            boolean available) {
        if (waitTimeoutMillis <= 0) {
            throw new IllegalArgumentException("waitTimeoutMillis must be > 0, got " + waitTimeoutMillis);
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
        this.onlineCheck = Objects.requireNonNull(onlineCheck, "onlineCheck");
        this.waitTimeoutMillis = waitTimeoutMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.mode = mode;
        this.delayCompletionCause = delayCompletionCause;
        this.available = available;
        this.phase = initialPhase;
        long timeoutTicks = SyncTicks.ticksForMillis(waitTimeoutMillis);
        // fixed-delay strategies are capped at the wait timeout, never beyond it
        this.armedDelayTicks = mode == Mode.EVENT_BOUND
                ? timeoutTicks
                : Math.min(delayTicks, timeoutTicks);
        this.recent = new RecentCompletions(waitTimeoutMillis, 256);
    }

    // ------------------------------------------------------------------ contract

    @Override
    public final SyncReadiness phase() {
        if (shutdown) {
            return SyncReadiness.FAILED;
        }
        return phase;
    }

    @Override
    public final boolean isAvailable() {
        return available && !shutdown;
    }

    @Override
    public final void waitForSync(UUID player, SyncCallback callback) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(callback, "callback");
        if (shutdown) {
            logger.warn("sync: {} is shut down — cannot wait for {}", id(), player);
            callback.onTimeout(TimeoutReason.INTEGRATION_ERROR);
            return;
        }
        if (mode == Mode.EVENT_BOUND && recent.took(player, clock.getAsLong())) {
            // the completion event fired between join and this call — data is already applied
            callback.onReady(new SyncReady(player, SyncCause.EVENT));
            return;
        }
        TaskHandle handle = schedulers.runGlobalDelayed(() -> fireTimeout(player), armedDelayTicks);
        PendingWait wait = new PendingWait(callback, handle);
        PendingWait previous = pending.put(player, wait);
        if (previous != null) {
            // an unfinished wait for the same player gets failed loudly, never silently dropped
            schedulers.cancel(previous.handle());
            TimeoutReason reason = TimeoutReason.INTEGRATION_ERROR;
            logger.warn("sync: replaced an unfinished wait for {} ({})", player, id());
            previous.callback().onTimeout(reason);
        }
    }

    @Override
    public final void shutdown() {
        shutdown = true;
        for (Map.Entry<UUID, PendingWait> entry : pending.entrySet()) {
            PendingWait wait = entry.getValue();
            schedulers.cancel(wait.handle());
            try {
                wait.callback().onTimeout(TimeoutReason.INTEGRATION_ERROR);
            } catch (Throwable t) {
                logger.error("sync: onTimeout callback threw during shutdown for {}: {}",
                        entry.getKey(), t.toString());
            }
        }
        pending.clear();
        Listener bridge = listenerBridge;
        if (bridge != null) {
            try {
                org.bukkit.event.HandlerList.unregisterAll(bridge);
            } catch (Throwable t) {
                logger.warn("sync: could not unregister the {} listener: {}", id(), t.toString());
            }
        }
    }

    // ------------------------------------------------------------------ signals

    /**
     * Record a completion for {@code uuid} (called by event bridges). Completes the
     * pending wait, if any, and remembers the completion briefly regardless —
     * see {@link RecentCompletions}. Returns true when a wait was waiting for it.
     */
    protected final boolean completeIfPending(UUID uuid, SyncCause cause) {
        if (uuid == null) {
            return false;
        }
        recent.offer(uuid, clock.getAsLong());
        PendingWait wait = pending.remove(uuid);
        if (wait == null) {
            return false;
        }
        schedulers.cancel(wait.handle());
        try {
            wait.callback().onReady(new SyncReady(uuid, cause));
        } catch (Throwable t) {
            logger.error("sync: onReady callback threw for {}: {}", uuid, t.toString());
        }
        return true;
    }

    /** Hook fired when the armed task ends the wait without a completion. */
    protected void onWaitExpired(UUID player, TimeoutReason reason, boolean online) {
        // subclasses may flip phase / log; default: nothing extra
    }

    protected final void setPhase(SyncReadiness newPhase) {
        this.phase = newPhase;
    }

    protected final Logger logger() {
        return logger;
    }

    protected final long waitTimeoutMillis() {
        return waitTimeoutMillis;
    }

    protected final long armedDelayTicks() {
        return armedDelayTicks;
    }

    // ------------------------------------------------------------------ internals

    private void fireTimeout(UUID player) {
        PendingWait wait = pending.remove(player);
        if (wait == null) {
            return; // already completed via an event; the task was cancelled
        }
        boolean online = safeOnline(player);
        if (mode == Mode.DELAY_BOUND && online) {
            wait.callback().onReady(new SyncReady(player, delayCompletionCause));
            return;
        }
        TimeoutReason reason = online ? TimeoutReason.WAIT_TIMEOUT : TimeoutReason.PLAYER_OFFLINE;
        onWaitExpired(player, reason, online);
        wait.callback().onTimeout(reason);
    }

    private boolean safeOnline(UUID player) {
        try {
            return onlineCheck.test(player);
        } catch (Throwable t) {
            logger.warn("sync: online check failed for {}: {}", player, t.toString());
            return false;
        }
    }

    private record PendingWait(SyncCallback callback, TaskHandle handle) {
    }
}
