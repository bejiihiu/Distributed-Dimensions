package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.interop.SourceServerName;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Wires the entity-transfer subsystem into the backend: the source portal listener, the
 * target-side arrival service, the global poll (2 s), the source sweep (2 s), the
 * stale-claim watchdog (5 min) and the dead-letter reaper (hourly). Every loop stops when
 * the module is disabled ({@link #shutdown()} from plugin disable).
 *
 * <p>The own-server name comes from {@link SourceServerName} (system property
 * {@code dimensions.source-server} / env {@code DD_SOURCE_SERVER}) — payloads are routed
 * by {@code target_server}, and the name must match what the source backends wrote.
 * When it cannot be resolved, the poll stays silent and the join fast-path idles;
 * source transfers still fail-closed (the portals refuse nothing — entities simply wait,
 * and the source sweep retracts them on its own timeout).
 */
public final class EntityTransferModule {

    private static final long POLL_INTERVAL_MILLIS = 2_000L;
    private static final long SWEEP_INTERVAL_MILLIS = 2_000L;
    private static final long WATCHDOG_INTERVAL_SECONDS = 300L;
    private static final long REAP_INTERVAL_MINUTES = 60L;
    private static final long DEAD_LETTER_AGE_MILLIS = Duration.ofDays(7).toMillis();
    private static final long STALE_CLAIM_AGE_MILLIS = Duration.ofMinutes(5).toMillis();

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final Optional<String> ownServer;
    private final EntityBackend backend;
    private final EntityTransferMetrics metrics;
    private final EntityTransferTracker tracker;
    private final EntityArrivalService arrivals;
    private final EntityPortalEventListener sourceListener;

    private EntityTransferModule(EntityBackend backend, EntityTransferMetrics metrics,
            EntityTransferTracker tracker, EntityArrivalService arrivals,
            EntityPortalEventListener sourceListener, Optional<String> ownServer) {
        this.backend = backend;
        this.metrics = metrics;
        this.tracker = tracker;
        this.arrivals = arrivals;
        this.sourceListener = sourceListener;
        this.ownServer = ownServer;
    }

    /**
     * Install the subsystem. {@code isMirage} reports the worlds that are stub landings —
     * entity portal events into them are ignored (the mirage guards handle those).
     */
    public static EntityTransferModule install(JavaPlugin plugin, PaperServices services,
            Predicate<World> isMirage) {
        if (!services.isHealthy()) {
            plugin.getSLF4JLogger().error("SEVERE: entity transfer subsystem not installed — backend is DEGRADED");
            return null;
        }
        PaperEntityBackend backend = new PaperEntityBackend(services, plugin);
        EntityTransferMetrics metrics = new EntityTransferMetrics();
        EntityTransferTracker tracker = new EntityTransferTracker(backend, metrics);
        Clock clock = Clock.systemUTC();
        EntityArrivalService arrivals = new EntityArrivalService(backend, metrics, plugin, clock);
        EntityPortalEventListener sourceListener =
                new EntityPortalEventListener(backend, metrics, tracker, plugin, clock, isMirage);
        EntityTransferModule module = new EntityTransferModule(backend, metrics, tracker, arrivals,
                sourceListener, Optional.ofNullable(SourceServerName.resolve()));
        module.enabled.set(true);

        plugin.getServer().getPluginManager().registerEvents(sourceListener, plugin);
        if (module.ownServer.isPresent()) {
            plugin.getServer().getPluginManager().registerEvents(
                    new EntityJoinListener(arrivals, backend.schedulers(), module.ownServer::get, module.enabled::get), plugin);
        } else {
            plugin.getSLF4JLogger().error("SEVERE: dimensions.source-server is not set (and DD_SOURCE_SERVER is missing) — "
                    + "entity payloads cannot be claimed by name; entity transfers are inert on this backend");
        }

        module.schedulePoll();
        module.scheduleSweep();
        module.scheduleWatchdog();
        module.scheduleReaper();
        return module;
    }

    /** Stop the poll/sweep/watchdog loops. Intended for plugin disable. */
    public void shutdown() {
        enabled.set(false);
    }

    /** Snapshot of counters — for diagnostics tooling. */
    public java.util.Map<String, Long> metricsSnapshot() {
        return metrics.snapshot();
    }

    /** Diagnostics for the tracked-assembly map — for support. */
    public int trackedAssemblyCount() {
        return tracker.trackedCount();
    }

    private void schedulePoll() {
        backend.schedulers().runAsync(() -> {
            if (!enabled.get()) {
                return;
            }
            String me = ownServer.orElse(null);
            if (me != null) {
                try {
                    arrivals.claimPending(me);
                } catch (RuntimeException e) {
                    backend.diagnostics().accept("entity payload poll failed: " + e.getMessage());
                }
            }
            backend.schedulers().runAsyncDelayedFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS, this::schedulePoll);
        });
    }

    private void scheduleSweep() {
        backend.schedulers().runAsync(() -> {
            if (!enabled.get()) {
                return;
            }
            for (String opId : tracker.trackedOpIds()) {
                List<kz.bejiihiu.dimensions.common.model.EntityTransferPayload> rows;
                try {
                    rows = backend.entityPayloads().findByOpId(opId);
                } catch (RuntimeException e) {
                    backend.diagnostics().accept("entity source sweep: findByOpId failed for op " + opId
                            + ": " + e.getMessage());
                    continue;
                }
                tracker.sweepOp(opId, rows, System.currentTimeMillis());
            }
            backend.schedulers().runAsyncDelayedFor(SWEEP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS, this::scheduleSweep);
        });
    }

    private void scheduleWatchdog() {
        backend.schedulers().runAsyncDelayedFor(WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS, () -> {
            if (!enabled.get()) {
                return;
            }
            arrivals.sweepStaleClaims(STALE_CLAIM_AGE_MILLIS);
            backend.schedulers().runAsyncDelayedFor(WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS, this::scheduleWatchdog);
        });
    }

    private void scheduleReaper() {
        backend.schedulers().runAsyncDelayedFor(REAP_INTERVAL_MINUTES, TimeUnit.MINUTES, () -> {
            if (!enabled.get()) {
                return;
            }
            arrivals.reapDeadLetters(DEAD_LETTER_AGE_MILLIS);
            backend.schedulers().runAsyncDelayedFor(REAP_INTERVAL_MINUTES, TimeUnit.MINUTES, this::scheduleReaper);
        });
    }
}
