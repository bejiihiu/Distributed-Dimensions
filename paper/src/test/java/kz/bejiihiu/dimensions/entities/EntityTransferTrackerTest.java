package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import kz.bejiihiu.dimensions.common.persistence.EntityPayloadStore;
import kz.bejiihiu.dimensions.common.persistence.InMemoryEntityPayloadStore;
import kz.bejiihiu.dimensions.paper.scheduler.FakeSchedulers;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source-side acknowledgement contract against a live in-memory store: rows gone =
 * retire the copies, any FAILED row = retract them, anything else = keep waiting.
 */
class EntityTransferTrackerTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private InMemoryEntityPayloadStore store;
    private FakeSchedulers schedulers;
    private EntityTransferMetrics metrics;
    private EntityTransferTracker tracker;

    /** Minimal backend seam — only the store and schedulers are exercised. */
    private final class FakeBackend implements EntityBackend {
        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public java.util.List<String> healthReasons() {
            return List.of();
        }

        @Override
        public kz.bejiihiu.dimensions.common.config.PluginConfig config() {
            throw new UnsupportedOperationException("not needed for the tracker");
        }

        @Override
        public kz.bejiihiu.dimensions.paper.DimensionResolver dimensions() {
            throw new UnsupportedOperationException("not needed for the tracker");
        }

        @Override
        public kz.bejiihiu.dimensions.paper.scheduler.Schedulers schedulers() {
            return schedulers;
        }

        @Override
        public kz.bejiihiu.dimensions.paper.chunktickets.ChunkTicketService chunkTickets() {
            throw new UnsupportedOperationException("not needed for the tracker");
        }

        @Override
        public EntityPayloadStore entityPayloads() {
            return store;
        }

        @Override
        public java.util.function.Consumer<String> diagnostics() {
            return message -> { };
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryEntityPayloadStore();
        schedulers = new FakeSchedulers();
        metrics = new EntityTransferMetrics();
        tracker = new EntityTransferTracker(new FakeBackend(), metrics);
    }

    private Entity fakeEntity() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("tracked");
        canned.answer("isValid", true);
        canned.answer("isDead", false);
        return ProxyEntities.entity(Item.class, EntityType.ITEM, canned);
    }

    private EntityTransferPayload row(String opId, int seq, EntityPayloadState state) {
        return new EntityTransferPayload(opId, seq, EntityPayloadKind.ITEM,
                "nether", "minecraft:nether", null,
                "{}", state, T0, T0);
    }

    @Test
    void rowsGoneSoTheSourceCopyIsRetired() {
        String opId = UUID.randomUUID().toString();
        Entity entity = fakeEntity();
        tracker.track(opId, List.of(entity), T0.toEpochMilli());
        // target already consumed the rows (insert then delete — as after a full spawn)
        store.insertAll(List.of(row(opId, 0, EntityPayloadState.PENDING)));
        store.deleteForOp(opId);

        var action = tracker.sweepOp(opId, store.findByOpId(opId), T0.toEpochMilli());
        assertEquals(SourceSweepDecision.Action.RETIRE, action);
        assertEquals(0, tracker.trackedCount());
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.OP_COMPLETED).longValue());
        assertTrue(schedulers.calls().contains("entity"),
                "expected an entity-scheduler removal task, got: " + schedulers.calls());
    }

    @Test
    void failedRowRetractsTheSourceCopy() {
        String opId = UUID.randomUUID().toString();
        Entity entity = fakeEntity();
        tracker.track(opId, List.of(entity), T0.toEpochMilli());
        store.insertAll(List.of(
                row(opId, 0, EntityPayloadState.CLAIMED),
                row(opId, 1, EntityPayloadState.FAILED)));

        var action = tracker.sweepOp(opId, store.findByOpId(opId), T0.toEpochMilli());
        assertEquals(SourceSweepDecision.Action.RETRACT, action);
        assertEquals(0, tracker.trackedCount());
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.SOURCE_RETRACTED).longValue());
    }

    @Test
    void pendingRowsWithinTimeoutKeepWaiting() {
        String opId = UUID.randomUUID().toString();
        Entity entity = fakeEntity();
        tracker.track(opId, List.of(entity), T0.toEpochMilli());
        store.insertAll(List.of(row(opId, 0, EntityPayloadState.PENDING)));

        var action = tracker.sweepOp(opId, store.findByOpId(opId), T0.toEpochMilli() + 1_000);
        assertEquals(SourceSweepDecision.Action.WAIT, action);
        assertEquals(1, tracker.trackedCount());
    }

    @Test
    void expiredAckTimeoutFailsTheOpAndKeepsTheEntity() {
        String opId = UUID.randomUUID().toString();
        Entity entity = fakeEntity();
        tracker.track(opId, List.of(entity), T0.toEpochMilli());
        store.insertAll(List.of(row(opId, 0, EntityPayloadState.CLAIMED)));

        var action = tracker.sweepOp(opId, store.findByOpId(opId),
                T0.toEpochMilli() + EntityTransferTracker.ACK_TIMEOUT_MILLIS);
        assertEquals(SourceSweepDecision.Action.EXPIRE, action);
        assertEquals(0, tracker.trackedCount());
        assertEquals(EntityPayloadState.FAILED, store.findByOpId(opId).get(0).state());
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.SOURCE_ACK_EXPIRED).longValue());
    }
}
