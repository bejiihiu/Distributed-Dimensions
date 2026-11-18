package kz.bejiihiu.dimensions.entities;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import kz.bejiihiu.dimensions.common.persistence.InMemoryEntityPayloadStore;
import kz.bejiihiu.dimensions.paper.TestWorlds;
import kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService;
import kz.bejiihiu.dimensions.paper.chunktickets.FakeChunkTicketGateway;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;
import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;
import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The destination-side arrival pipeline: the poll stays async for the database claim, every
 * world touch happens inside the region owning the working chunk, and the spawn itself runs
 * on the region owning the placement chunk. Region hops that come back with no task handle
 * mark the rows FAILED — they are never deleted, so the source side retracts on its own
 * timeout instead of losing the entity.
 *
 * <p>The spawn chain is exercised as far as a headless environment honestly allows: the
 * payload uses an unknown entity type key, so {@code EntitySpawner} degrades the node
 * instead of calling the (server-provided) entity registry. The rows therefore stay CLAIMED
 * and nothing is acknowledged — which is exactly what must happen until a spawn truly
 * completes in a world.
 */
class EntityArrivalServiceTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final String TARGET = "dest-server";
    private static final String OP = "3f6b0d2e-9c1a-4a2f-8b7d-5e0f9a1c3b55";

    @TempDir
    Path tempDir;

    private InMemoryEntityPayloadStore store;
    private FakeSchedulers schedulers;
    private EntityTransferMetrics metrics;
    private World destination;
    private TestBackend backend;
    private Plugin plugin;
    private EntityArrivalService arrivals;

    @BeforeEach
    void setUp() {
        store = new InMemoryEntityPayloadStore();
        schedulers = new FakeSchedulers();
        metrics = new EntityTransferMetrics();
        destination = TestWorlds.create("world", World.Environment.NORMAL);
        plugin = fakePlugin();
        backend = new TestBackend(schedulers);
        arrivals = new EntityArrivalService(backend, metrics, plugin,
                Clock.fixed(T0, ZoneOffset.UTC), env -> destination);
    }

    /** A fresh service of the same shape wired to its own recording backend. */
    private EntityTransferPayload payloadRow(String opId) {
        return payloadRow(opId, 12.25, 70.0, -8.5);
    }

    private EntityTransferPayload payloadRow(String opId, double x, double y, double z) {
        JsonObject arrival = new JsonObject();
        arrival.addProperty("cause", "world_spawn");
        arrival.addProperty("x", x);
        arrival.addProperty("y", y);
        arrival.addProperty("z", z);
        arrival.addProperty("yaw", 0.0);
        arrival.addProperty("pitch", 0.0);
        arrival.addProperty("right", 0.5);
        arrival.addProperty("up", 0.0);
        arrival.addProperty("forward", 0.0);
        arrival.addProperty("ew", 0.6);
        arrival.addProperty("eh", 1.8);
        arrival.addProperty("zeroForward", true);
        arrival.addProperty("hadPlayer", false);

        JsonObject velocity = new JsonObject();
        velocity.addProperty("x", 0.0);
        velocity.addProperty("y", 0.0);
        velocity.addProperty("z", 0.0);

        JsonObject fields = new JsonObject();
        fields.addProperty("yaw", 0.0);
        fields.addProperty("pitch", 0.0);
        fields.add("velocity", velocity);
        fields.add("arrival", arrival);

        EntityNode root = new EntityNode(EntityPayloadKind.UNKNOWN_DEGRADED, "!!invalid!!", -1, 0, fields);
        String json = new Gson().toJson(EntityCodec.nodeBody(root));
        return EntityTransferPayload.pending(opId, 0, EntityPayloadKind.UNKNOWN_DEGRADED,
                TARGET, "minecraft:overworld", null, json, T0);
    }

    @Test
    void pollClaimsAndEnqueuesDestinationRegionTask() {
        store.insertAll(List.of(payloadRow(OP)));

        int claimed = arrivals.claimPending(TARGET);

        assertEquals(1, claimed);
        assertEquals(EntityPayloadState.CLAIMED, store.findByOpId(OP).get(0).state());
        // the poll thread enqueued the region task for the hint chunk (12.25, -8.5) -> (0, -1)
        assertEquals(List.of("region-location:0,-1"), schedulers.calls());
        // nothing spawned yet — the rows must not be acknowledged before the region work
        assertEquals(0L, metrics.snapshot().getOrDefault(EntityTransferMetrics.NODE_SPAWNED, 0L));
        assertEquals(1, store.countForOp(OP));
    }

    @Test
    void fullClaimCycleRunsOnDestinationRegionThenPlacementRegion() {
        store.insertAll(List.of(payloadRow(OP)));
        arrivals.claimPending(TARGET);

        schedulers.advanceTicks(1);

        List<String> calls = schedulers.calls();
        assertTrue(calls.contains("region-location:0,-1"),
                "expected the hint-chunk hop, got: " + calls);
        // world_spawn places at the world spawn (0, 64, 0) — the spawn hop must land on
        // the region owning THAT chunk, not the hint chunk and not the poll thread
        assertTrue(calls.contains("region-location:0,0"),
                "expected the spawn hop on the placement chunk, got: " + calls);
        // the spawn itself ran (and degraded as expected in a headless environment) —
        // it did NOT complete, so the rows stay CLAIMED and are never deleted
        assertTrue(metrics.snapshot().getOrDefault(EntityTransferMetrics.SPAWN_FAILED, 0L) >= 1,
                "expected the spawn to have run and failed structurally, got: " + metrics.snapshot());
        assertEquals(EntityPayloadState.CLAIMED, store.findByOpId(OP).get(0).state());
        assertEquals(1, store.countForOp(OP));
    }

    @Test
    void nullHandleOnTheClaimHopMarksRowsFailedAndNeverDeletes() {
        NullHopSchedulers nullFirst = new NullHopSchedulers(schedulers, 1);
        TestBackend local = new TestBackend(nullFirst);
        EntityArrivalService failing = new EntityArrivalService(local, metrics, plugin,
                Clock.fixed(T0, ZoneOffset.UTC), env -> destination);
        store.insertAll(List.of(payloadRow(OP)));

        int claimed = failing.claimPending(TARGET);

        assertEquals(0, claimed);
        assertEquals(EntityPayloadState.FAILED, store.findByOpId(OP).get(0).state());
        assertEquals(1, store.countForOp(OP), "rows must survive a hop failure — the source retracts");
        assertEquals(1L, metrics.snapshot().getOrDefault(EntityTransferMetrics.OP_FAILED, 0L));
        assertTrue(local.diagnosticLog().stream().anyMatch(d -> d.contains("no task handle")),
                "expected the hop failure to be diagnosed, got: " + local.diagnosticLog());
    }

    @Test
    void nullHandleOnTheSpawnHopMarksRowsFailedAndNeverDeletes() {
        NullHopSchedulers nullSecond = new NullHopSchedulers(schedulers, 2);
        TestBackend local = new TestBackend(nullSecond);
        EntityArrivalService failing = new EntityArrivalService(local, metrics, plugin,
                Clock.fixed(T0, ZoneOffset.UTC), env -> destination);
        store.insertAll(List.of(payloadRow(OP)));

        int claimed = failing.claimPending(TARGET);

        assertEquals(1, claimed, "the claim itself is fine; the placement hop is what fails");
        schedulers.advanceTicks(1);

        assertEquals(EntityPayloadState.FAILED, store.findByOpId(OP).get(0).state());
        assertEquals(1, store.countForOp(OP), "rows must survive a hop failure — the source retracts");
        assertEquals(1L, metrics.snapshot().getOrDefault(EntityTransferMetrics.OP_FAILED, 0L));
        assertTrue(local.diagnosticLog().stream().anyMatch(d -> d.contains("placement chunk")),
                "expected the placement hop failure to be diagnosed, got: " + local.diagnosticLog());
    }

    @Test
    void unresolvableDestinationWorldMarksRowsFailed() {
        TestBackend local = new TestBackend(schedulers);
        EntityArrivalService orphan = new EntityArrivalService(local, metrics, plugin,
                Clock.fixed(T0, ZoneOffset.UTC), env -> null);
        store.insertAll(List.of(payloadRow(OP)));

        int claimed = orphan.claimPending(TARGET);

        assertEquals(0, claimed);
        assertEquals(EntityPayloadState.FAILED, store.findByOpId(OP).get(0).state());
        assertEquals(1, store.countForOp(OP));
        assertTrue(local.diagnosticLog().stream().anyMatch(d -> d.contains("cannot resolve a world")));
    }

    @Test
    void nonFiniteArrivalCoordinatesRejectTheOp() {
        // a hostile-or-broken source can emit a JSON number that overflows to infinity —
        // the wire text is parsed as a number, so the guard in the hint builder must fire
        String json = "{\"kind\":\"unknown_degraded\",\"type\":\"!!invalid!!\",\"parent\":-1,\"depth\":0,"
                + "\"fields\":{\"velocity\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"arrival\":{\"cause\":\"world_spawn\",\"x\":1e999,\"y\":70.0,\"z\":-8.5}}}";
        EntityTransferPayload overflow = EntityTransferPayload.pending(OP, 0, EntityPayloadKind.UNKNOWN_DEGRADED,
                TARGET, "minecraft:overworld", null, json, T0);
        store.insertAll(List.of(overflow));

        int claimed = arrivals.claimPending(TARGET);

        assertEquals(0, claimed);
        assertEquals(EntityPayloadState.FAILED, store.findByOpId(OP).get(0).state());
        assertEquals(1, store.countForOp(OP));
        assertTrue(backend.diagnosticLog().stream().anyMatch(d -> d.contains("non-finite")));
    }

    // ------------------------------------------------------------------ fakes

    /**
     * Delegates everything to {@link FakeSchedulers} but returns {@code null} for the
     * {@code n}-th (1-based) location-addressed region hop — the “region scheduler
     * produced no task” failure the arrival path must survive.
     */
    private static final class NullHopSchedulers implements Schedulers {

        private final FakeSchedulers delegate;
        private final int failOnHop;
        private int locationHops;

        private NullHopSchedulers(FakeSchedulers delegate, int failOnHop) {
            this.delegate = delegate;
            this.failOnHop = failOnHop;
        }

        private TaskHandle maybeNull(TaskHandle handle) {
            locationHops++;
            return locationHops >= failOnHop ? null : handle;
        }

        @Override
        public TaskHandle runOn(org.bukkit.entity.Entity entity, Runnable action) {
            return delegate.runOn(entity, action);
        }

        @Override
        public TaskHandle runOn(org.bukkit.entity.Entity entity, Runnable action, Runnable retired) {
            return delegate.runOn(entity, action, retired);
        }

        @Override
        public TaskHandle runOnDelayed(org.bukkit.entity.Entity entity, Runnable action, long delayTicks) {
            return delegate.runOnDelayed(entity, action, delayTicks);
        }

        @Override
        public TaskHandle runOnDelayed(org.bukkit.entity.Entity entity, Runnable action, Runnable retired,
                long delayTicks) {
            return delegate.runOnDelayed(entity, action, retired, delayTicks);
        }

        @Override
        public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action) {
            return delegate.runOnRegion(world, chunkX, chunkZ, action);
        }

        @Override
        public TaskHandle runOnRegion(World world, int chunkX, int chunkZ, Runnable action, long delayTicks) {
            return delegate.runOnRegion(world, chunkX, chunkZ, action, delayTicks);
        }

        @Override
        public TaskHandle runOnRegion(org.bukkit.Location location, Runnable action) {
            return maybeNull(delegate.runOnRegion(location, action));
        }

        @Override
        public TaskHandle runOnRegion(org.bukkit.Location location, Runnable action, long delayTicks) {
            return delegate.runOnRegion(location, action, delayTicks);
        }

        @Override
        public TaskHandle runGlobal(Runnable action) {
            return delegate.runGlobal(action);
        }

        @Override
        public TaskHandle runGlobalDelayed(Runnable action, long delayTicks) {
            return delegate.runGlobalDelayed(action, delayTicks);
        }

        @Override
        public TaskHandle runAsync(Runnable action) {
            return delegate.runAsync(action);
        }

        @Override
        public TaskHandle runAsyncDelayedFor(long delay, java.util.concurrent.TimeUnit unit, Runnable action) {
            return delegate.runAsyncDelayedFor(delay, unit, action);
        }
    }

    /** Diagnostics-recording backend; the chunk-ticket service is the real one on fakes. */
    private final class TestBackend implements EntityBackend {
        private final Schedulers hopSchedulers;
        private final List<String> diagnostics = new CopyOnWriteArrayList<>();

        private TestBackend(Schedulers hopSchedulers) {
            this.hopSchedulers = hopSchedulers;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public List<String> healthReasons() {
            return List.of();
        }

        @Override
        public kz.bejiihiu.dimensions.common.config.PluginConfig config() {
            return kz.bejiihiu.dimensions.paper.transfer.FakeTransferBackend.loadDefaults(tempDir);
        }

        @Override
        public kz.bejiihiu.dimensions.paper.DimensionResolver dimensions() {
            throw new UnsupportedOperationException("not needed for the arrival pipeline");
        }

        @Override
        public Schedulers schedulers() {
            return hopSchedulers;
        }

        @Override
        public ChunkTicketService chunkTickets() {
            return new ChunkTicketService(schedulers, new FakeChunkTicketGateway(), schedulers::currentTick,
                    true, true, message -> { });
        }

        @Override
        public kz.bejiihiu.dimensions.common.persistence.EntityPayloadStore entityPayloads() {
            return store;
        }

        @Override
        public Consumer<String> diagnostics() {
            return diagnostics::add;
        }

        private List<String> diagnosticLog() {
            return List.copyOf(diagnostics);
        }
    }

    private static Plugin fakePlugin() {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "namespace" -> "dd_test";
            case "getName" -> "TestPlugin";
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "TestPlugin";
            default -> throw new UnsupportedOperationException(
                    "TestPlugin refuses " + method.getName());
        };
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
