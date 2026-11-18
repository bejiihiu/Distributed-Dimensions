package kz.bejiihiu.dimensions.paper.scheduler;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Deterministic, manually-driven {@link Schedulers} implementation for tests.
 *
 * <p>Semantics, deliberately faithful to the production path:
 * <ul>
 *   <li>one monotonic clock, kept in milliseconds (50 ms per tick, matching Minecraft);</li>
 *   <li>tick-based schedules (entity / region / global) are due after {@code delayTicks * 50} ms;
 *       the async API is time-based and lands on the same clock via {@link TimeUnit#toMillis(long)};</li>
 *   <li>tasks due at the same millisecond run in FIFO order;</li>
 *   <li>{@link #advanceTicks(long)} / {@link #advanceMillis(long)} execute every task due
 *       in the stepped interval, in due order;</li>
 *   <li>cancelled tasks never run;
 * </li>
 * </ul>
 *
 * <p>Routes are logged to {@link #calls()} for routing assertions, e.g.
 * {@code "entity"}, {@code "region:<x>,<z>"}, {@code "region-location"}, {@code "global"},
 * {@code "async"}. Use {@code calls()} instead of poking at bukkit types.
 */
public final class FakeSchedulers implements Schedulers {

    public static final long MILLIS_PER_TICK = 50L;

    private long nowMillis;
    private long sequence;
    private final PriorityQueue<Scheduled> queue =
            new PriorityQueue<>(Comparator.comparingLong(Scheduled::dueMillis).thenComparingLong(Scheduled::sequence));
    private final Queue<String> calls = new ArrayDeque<>();

    /** Current time in milliseconds. */
    public long currentMillis() {
        return nowMillis;
    }

    /** Current time in ticks (50 ms per tick). */
    public long currentTick() {
        return nowMillis / MILLIS_PER_TICK;
    }

    /** LongSupplier view suitable for ChunkTicketService's tick source. */
    public LongSupplier tickSupplier() {
        return this::currentTick;
    }

    /** Advance the clock executing everything due in between, in due order. */
    public void advanceTicks(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be >= 0, got " + ticks);
        }
        advanceMillis(ticks * MILLIS_PER_TICK);
    }

    /** Advance the clock executing everything due in between, in due order. */
    public void advanceMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must be >= 0, got " + millis);
        }
        long target = nowMillis + millis;
        while (true) {
            Scheduled next = queue.peek();
            if (next == null || next.dueMillis() > target) {
                break;
            }
            queue.poll();
            nowMillis = next.dueMillis();
            next.task().runIfNotCancelled();
        }
        nowMillis = target;
    }

    /** Tasks currently queued (not yet executed). */
    public int pendingTasks() {
        return queue.size();
    }

    /** Routing log of every schedule call, oldest first. */
    public List<String> calls() {
        return new ArrayList<>(calls);
    }

    // ------------------------------------------------------------------ routes

    @Override
    @Nullable
    public TaskHandle runOn(Entity entity, Runnable action) {
        return runOn(entity, action, null);
    }

    @Override
    @Nullable
    public TaskHandle runOn(Entity entity, Runnable action, @Nullable Runnable retired) {
        Objects.requireNonNull(entity, "entity");
        calls.add("entity");
        return schedule(nowMillis, action);
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
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        calls.add("entity-delayed:" + delayTicks);
        return schedule(nowMillis + delayTicks * MILLIS_PER_TICK, action);
    }

    @Override
    public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action) {
        return runOnRegion(world, chunkX, chunkZ, action, 0);
    }

    @Override
    public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action, long delayTicks) {
        Objects.requireNonNull(world, "world");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        calls.add("region:" + chunkX + "," + chunkZ + (delayTicks > 0 ? ":" + delayTicks : ""));
        return schedule(nowMillis + delayTicks * MILLIS_PER_TICK, action);
    }

    @Override
    public TaskHandle runOnRegion(Location location, Runnable action) {
        return runOnRegion(location, action, 0);
    }

    @Override
    public TaskHandle runOnRegion(Location location, Runnable action, long delayTicks) {
        Objects.requireNonNull(location, "location");
        calls.add("region-location:" + RegionMath.chunkCoordinate(location.getBlockX())
                + "," + RegionMath.chunkCoordinate(location.getBlockZ()) + (delayTicks > 0 ? ":" + delayTicks : ""));
        return schedule(nowMillis + delayTicks * MILLIS_PER_TICK, action);
    }

    @Override
    public TaskHandle runGlobal(Runnable action) {
        return runGlobalDelayed(action, 0);
    }

    @Override
    public TaskHandle runGlobalDelayed(Runnable action, long delayTicks) {
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks must be >= 0, got " + delayTicks);
        }
        calls.add("global" + (delayTicks > 0 ? ":" + delayTicks : ""));
        return schedule(nowMillis + delayTicks * MILLIS_PER_TICK, action);
    }

    @Override
    public TaskHandle runAsync(Runnable action) {
        calls.add("async");
        return schedule(nowMillis, action);
    }

    @Override
    public TaskHandle runAsyncDelayedFor(long delay, TimeUnit unit, Runnable action) {
        Objects.requireNonNull(unit, "unit");
        if (delay < 0) {
            throw new IllegalArgumentException("delay must be >= 0, got " + delay);
        }
        calls.add("async-delayed:" + unit.toMillis(delay) + "ms");
        return schedule(nowMillis + unit.toMillis(delay), action);
    }

    // ------------------------------------------------------------------ internals

    private TaskHandle schedule(long dueMillis, Runnable action) {
        Objects.requireNonNull(action, "action");
        FakeTask task = new FakeTask(action);
        queue.add(new Scheduled(dueMillis, sequence++, task));
        return task;
    }

    private record Scheduled(long dueMillis, long sequence, FakeTask task) {
    }
}
