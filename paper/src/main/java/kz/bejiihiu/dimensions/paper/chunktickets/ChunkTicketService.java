package kz.bejiihiu.dimensions.paper.chunktickets;

import kz.bejiihiu.dimensions.common.config.ChunkTicketsSettings;
import kz.bejiihiu.dimensions.paper.scheduler.RegionMath;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;
import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Plugin chunk tickets for portal-arrival chunks: keeps the destination chunk (and
 * optionally a one-chunk ring around it) loaded for a short TTL, refreshed on every
 * transfer, then releases the ticket.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Plugin tickets have no TTL — expiry is reproduced here: each touch (re)arms a
 *       delayed region-scheduler removal task for the chunk.</li>
 *   <li>Every timer goes through the {@link Schedulers} facade; raw bukkit scheduling
 *       is never used.</li>
 *   <li>Ticket mutations run inside the region that owns the chunk. The bookkeeping
 *       ({@link TicketLedger}) is platform-agnostic and unit-tested directly.</li>
 *   <li>A strong world reference is kept per tracked chunk (entries are dropped as soon
 *       as the ticket expires). {@link #close()} removes every tracked ticket on disable;
 *       failures are reported through the diagnostics sink, never swallowed silently.</li>
 * </ul>
 *
 * <p>Ring-of-8: when enabled, every touch also refreshes the 8 chunks surrounding the
 * center. Toggle from {@code entities.cross-server-transfer.chunk-tickets.ring}.
 */
public final class ChunkTicketService implements AutoCloseable {

    /** 300 ticks = 15 seconds, matching the vanilla destination-chunk loader window. */
    public static final long TTL_TICKS = TicketLedger.DEFAULT_TTL_TICKS;

    private final Schedulers schedulers;
    private final ChunkTicketGateway gateway;
    private final LongSupplier tickSource;
    private final boolean enabled;
    private final boolean ring;
    private final Consumer<String> diagnostics;

    private final TicketLedger ledger = new TicketLedger();
    private final Map<TicketKey, World> worlds = new ConcurrentHashMap<>();
    private final Map<TicketKey, TaskHandle> pendingRemovals = new ConcurrentHashMap<>();

    public ChunkTicketService(Schedulers schedulers, ChunkTicketGateway gateway,
            LongSupplier tickSource, boolean enabled, boolean ring, Consumer<String> diagnostics) {
        this.schedulers = Objects.requireNonNull(schedulers, "schedulers");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource");
        this.enabled = enabled;
        this.ring = ring;
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
    }

    /**
     * Production wiring: real gateway (world resolution via UIDs), real tick source,
     * config-driven switches.
     */
    public static ChunkTicketService create(Plugin plugin, Schedulers schedulers,
            ChunkTicketsSettings settings) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(settings, "settings");
        Consumer<String> sink = message -> plugin.getSLF4JLogger().warn("chunk-tickets: {}", message);
        return new ChunkTicketService(schedulers, new WorldChunkTicketGateway(plugin),
                Bukkit::getCurrentTick, settings.enabled(), settings.ring(), sink);
    }

    /** True when the service is enabled by configuration. A disabled service is a no-op. */
    public boolean enabled() {
        return enabled;
    }

    /** True when the ring-of-8 behaviour is enabled by configuration. */
    public boolean ringEnabled() {
        return ring;
    }

    /**
     * Ensure the ticket for the chunk is held and its expiry is pushed TTL into the future.
     * Safe to call from any thread; the ticket mutation happens inside the owning region.
     */
    public void addTicket(World world, int chunkX, int chunkZ) {
        Objects.requireNonNull(world, "world");
        if (!enabled) {
            return;
        }
        TicketKey center = new TicketKey(world.getUID().toString(), chunkX, chunkZ);
        apply(world, center);
        if (ring) {
            for (TicketKey neighbour : TicketKey.neighbors(center)) {
                apply(world, neighbour);
            }
        }
    }

    /** Same as {@link #addTicket(World, int, int)} resolved from block coordinates. */
    public void addTicketForBlock(World world, int blockX, int blockZ) {
        Objects.requireNonNull(world, "world");
        addTicket(world, RegionMath.chunkCoordinate(blockX), RegionMath.chunkCoordinate(blockZ));
    }

    /**
     * Number of chunks currently tracked (center + ring). Exposed for diagnostics;
     * consumers must not treat it as a synchronization primitive.
     */
    public int trackedCount() {
        return ledger.size();
    }

    /**
     * Cancel pending removal tasks and drop every tracked ticket. Best effort — failures
     * are reported through diagnostics. Intended to be called on plugin disable.
     */
    @Override
    public void close() {
        for (TaskHandle handle : pendingRemovals.values()) {
            schedulers.cancel(handle);
        }
        pendingRemovals.clear();
        for (TicketKey key : ledger.keys()) {
            if (!ledger.forget(key)) {
                continue;
            }
            worlds.remove(key);
            removeQuietly(key);
        }
        worlds.clear();
    }

    // ------------------------------------------------------------------ internals

    private void apply(World world, TicketKey key) {
        schedulers.runOnRegion(world, key.chunkX(), key.chunkZ(), () -> {
            long now = tickSource.getAsLong();
            TicketLedger.Touch touch = ledger.touch(key, now);
            worlds.put(key, world);
            try {
                if (!gateway.add(key)) {
                    diagnostics.accept("could not apply plugin ticket for " + describe(key)
                            + " (world unavailable) — chunk will not stay loaded");
                }
            } catch (RuntimeException e) {
                diagnostics.accept("failed to apply plugin ticket for " + describe(key)
                        + ": " + e.getMessage());
            }
            rearm(world, key, touch.expiresAtTick() - now);
        });
    }

    /**
     * (Re)arm the delayed removal task. Any previously armed removal for this chunk is
     * cancelled — this is what turns every touch into a refresh instead of an
     * append-only grant.
     */
    private void rearm(World world, TicketKey key, long delayTicks) {
        TaskHandle newHandle = schedulers.runOnRegion(world, key.chunkX(), key.chunkZ(),
                () -> expire(key), delayTicks);
        TaskHandle previous = pendingRemovals.put(key, newHandle);
        if (previous != null) {
            schedulers.cancel(previous);
        }
    }

    private void expire(TicketKey key) {
        long now = tickSource.getAsLong();
        if (!ledger.expireIfDue(key, now)) {
            // Refreshed in the race window between expiry check and execution — keep the ticket.
            return;
        }
        pendingRemovals.remove(key);
        worlds.remove(key);
        removeQuietly(key);
    }

    private void removeQuietly(TicketKey key) {
        try {
            if (!gateway.remove(key)) {
                diagnostics.accept("plugin ticket for " + describe(key)
                        + " could not be removed (world unavailable or ticket already gone)");
            }
        } catch (RuntimeException e) {
            diagnostics.accept("failed to remove plugin ticket for " + describe(key)
                    + ": " + e.getMessage());
        }
    }

    private static String describe(TicketKey key) {
        return "chunk [" + key.chunkX() + ", " + key.chunkZ() + "] in world " + key.worldKey();
    }
}

