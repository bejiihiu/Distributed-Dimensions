package kz.bejiihiu.dimensions.paper.scheduler;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Single scheduling surface for the whole plugin, valid on both Paper and Folia.
 *
 * <p>Paper 1.20+ executes the Folia-style scheduler APIs natively (region, global
 * region, async and entity schedulers), so this facade has exactly one implementation —
 * {@link PlatformSchedulers} — and no platform branching anywhere. Folia region
 * boundaries are assigned at runtime by the server and are not exposed here; the only
 * coordinate math the facade performs is block → chunk ({@link RegionMath}).
 *
 * <p>Semantics contract (see docs/folia.md for the full matrix):
 * <ul>
 *   <li>{@code runOn(Entity, ...)} — work with a specific entity; the task follows the
 *       entity. Returns {@code null} when the entity is already retired (removed); such a
 *       task is never scheduled.</li>
 *   <li>{@code runOnRegion(World, chunkX, chunkZ, ...)} — work with blocks/state of one
 *       region. Chunk coordinates, not block coordinates.</li>
 *   <li>{@code runGlobal(...)} — server-global logic that does not belong to any region
 *       (time, weather, console commands).</li>
 *   <li>{@code runAsync(...)} — network, database, heavy computation. No Bukkit world
 *       state may be touched from here.</li>
 * </ul>
 *
 * <p>Never call Bukkit APIs from arbitrary async threads. Delay unit is ticks for
 * entity/region/global schedules and {@link TimeUnit} for async schedules (the platform
 * async API is time-based, not tick-based).
 */
public interface Schedulers {

    // ------------------------------------------------------------------ entity

    /** Schedule on the entity scheduler. {@code null} handle when the entity is retired. */
    @Nullable
    TaskHandle runOn(Entity entity, Runnable action);

    /**
     * Same as {@link #runOn(Entity, Runnable)} with an explicit retire callback.
     * The retire callback runs in critical code: no world access, no chunk loads,
     * no ticket changes are permitted there.
     */
    @Nullable
    TaskHandle runOn(Entity entity, Runnable action, @Nullable Runnable retired);

    /** Delayed entity schedule. {@code delayTicks} may be 0 for next-tick. */
    @Nullable
    TaskHandle runOnDelayed(Entity entity, Runnable action, long delayTicks);

    /** Delayed entity schedule with retire callback. */
    @Nullable
    TaskHandle runOnDelayed(Entity entity, Runnable action, @Nullable Runnable retired, long delayTicks);

    // ------------------------------------------------------------------ region

    /** Schedule on the region owning the given chunk. */
    TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action);

    /** Delayed region schedule. {@code delayTicks} may be 0 for next-tick. */
    TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action, long delayTicks);

    /** Schedule on the region owning the location. */
    TaskHandle runOnRegion(Location location, Runnable action);

    /** Delayed region schedule. {@code delayTicks} may be 0 for next-tick. */
    TaskHandle runOnRegion(Location location, Runnable action, long delayTicks);

    /** Convenience: schedule on the region owning the given block coordinates. */
    default TaskHandle runOnRegionBlock(World world, int blockX, int blockZ, Runnable action) {
        Objects.requireNonNull(world, "world");
        return runOnRegion(world, RegionMath.chunkCoordinate(blockX),
                RegionMath.chunkCoordinate(blockZ), action);
    }

    // ------------------------------------------------------------------ global

    /** Schedule on the global region. */
    TaskHandle runGlobal(Runnable action);

    /** Delayed global schedule. {@code delayTicks} may be 0 for next-tick. */
    TaskHandle runGlobalDelayed(Runnable action, long delayTicks);

    // ------------------------------------------------------------------ async

    /** Schedule asynchronously, immediately. */
    TaskHandle runAsync(Runnable action);

    /** Delayed async schedule. The platform async API is time-based, hence {@link TimeUnit}. */
    TaskHandle runAsyncDelayedFor(long delay, TimeUnit unit, Runnable action);

    // ------------------------------------------------------------------ cancel

    /**
     * Cancel a handle previously returned by this facade. {@code null} handles are
     * ignored, which makes this safe on the {@code @Nullable} entity paths.
     */
    default void cancel(@Nullable TaskHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }
}
