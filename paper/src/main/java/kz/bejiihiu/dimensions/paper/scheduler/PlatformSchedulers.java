package kz.bejiihiu.dimensions.paper.scheduler;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.Nullable;

/**
 * The one and only {@link Schedulers} implementation.
 *
 * <p>Both Paper and Folia implement the same {@code io.papermc.paper.threadedregions.scheduler}
 * interfaces ({@code EntityScheduler}, {@code RegionScheduler}, {@code GlobalRegionScheduler},
 * {@code AsyncScheduler}). On Paper the tasks are handled internally like regular tasks; on
 * Folia they execute on the owning region thread. This class therefore contains zero platform
 * detection code and is safe on both.
 */
public final class PlatformSchedulers implements Schedulers {

    private final Plugin plugin;

    public PlatformSchedulers(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    // ------------------------------------------------------------------ entity

    @Override
    @Nullable
    public TaskHandle runOn(Entity entity, Runnable action) {
        return runOn(entity, action, null);
    }

    @Override
    @Nullable
    public TaskHandle runOn(Entity entity, Runnable action, @Nullable Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        ScheduledTask task = entity.getScheduler().run(plugin, $ -> action.run(), retired);
        return of(task);
    }

    @Override
    @Nullable
    public TaskHandle runOnDelayed(Entity entity, Runnable action, long delayTicks) {
        return runOnDelayed(entity, action, null, delayTicks);
    }

    @Override
    @Nullable
    public TaskHandle runOnDelayed(Entity entity, Runnable action, @Nullable Runnable retired, long delayTicks) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(action, "action");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        ScheduledTask task = entity.getScheduler().runDelayed(plugin, $ -> action.run(), retired, delayTicks);
        return of(task);
    }

    // ------------------------------------------------------------------ region

    @Override
    public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action) {
        return runOnRegion(world, chunkX, chunkZ, action, 0);
    }

    @Override
    public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action, long delayTicks) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(action, "action");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        var regionScheduler = plugin.getServer().getRegionScheduler();
        ScheduledTask task = delayTicks == 0
                ? regionScheduler.run(plugin, world, chunkX, chunkZ, toTask(action))
                : regionScheduler.runDelayed(plugin, world, chunkX, chunkZ, toTask(action), delayTicks);
        return of(task);
    }

    @Override
    public TaskHandle runOnRegion(Location location, Runnable action) {
        return runOnRegion(location, action, 0);
    }

    @Override
    public TaskHandle runOnRegion(Location location, Runnable action, long delayTicks) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(action, "action");
        Location copied = location.clone();
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        var regionScheduler = plugin.getServer().getRegionScheduler();
        ScheduledTask task = delayTicks == 0
                ? regionScheduler.run(plugin, copied, toTask(action))
                : regionScheduler.runDelayed(plugin, copied, toTask(action), delayTicks);
        return of(task);
    }

    // ------------------------------------------------------------------ global

    @Override
    public TaskHandle runGlobal(Runnable action) {
        return runGlobalDelayed(action, 0);
    }

    @Override
    public TaskHandle runGlobalDelayed(Runnable action, long delayTicks) {
        Objects.requireNonNull(action, "action");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        var global = plugin.getServer().getGlobalRegionScheduler();
        ScheduledTask task = delayTicks == 0
                ? global.run(plugin, toTask(action))
                : global.runDelayed(plugin, toTask(action), delayTicks);
        return of(task);
    }

    // ------------------------------------------------------------------ async

    @Override
    public TaskHandle runAsync(Runnable action) {
        Objects.requireNonNull(action, "action");
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runNow(plugin, toTask(action));
        return of(task);
    }

    @Override
    public TaskHandle runAsyncDelayedFor(long delay, TimeUnit unit, Runnable action) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(action, "action");
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0, got " + delay);
        }
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runDelayed(plugin, toTask(action), delay, unit);
        return of(task);
    }

    // ------------------------------------------------------------------ helpers

    private static Consumer<ScheduledTask> toTask(Runnable action) {
        return $ -> action.run();
    }

    @Nullable
    private static TaskHandle of(@Nullable ScheduledTask task) {
        return task == null ? null : new ScheduledTaskHandle(task);
    }

    private record ScheduledTaskHandle(ScheduledTask task) implements TaskHandle {

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }
}
