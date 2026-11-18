package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.velocity.support.AdjustableClock;
import kz.bejiihiu.dimensions.velocity.support.FakeServerConnection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The idempotency registry: begin/complete/replay/conflict semantics, abandonment,
 * the recent-operations ring, and capacity bounds.
 */
class TransferOperationsTest {

    private final AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-18T12:00:00Z"));

    private TransferOperations newOperations() {
        return new TransferOperations(clock);
    }

    private FakeServerConnection source(String server) {
        return new FakeServerConnection(server, 25_600);
    }

    @Test
    void firstBeginIsFreshAndDuplicateBeginIsInFlight() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        FakeServerConnection src = source("overworld");

        assertEquals(TransferOperations.BeginStatus.FRESH, ops.begin(id, player, "overworld", "nether", src).status());
        assertEquals(TransferOperations.BeginStatus.IN_FLIGHT, ops.begin(id, player, "overworld", "nether", src).status());
        assertEquals(1, ops.pendingSize());
        assertEquals(1, ops.stats().inFlightReplays.get());
    }

    @Test
    void sameRequestIdWithDifferentCoordinatesIsAConflict() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ops.begin(id, player, "overworld", "nether", source("overworld"));

        // different target server - an id can never name two operations
        assertEquals(TransferOperations.BeginStatus.CONFLICT,
                ops.begin(id, player, "overworld", "end", source("overworld")).status());
        // different player
        assertEquals(TransferOperations.BeginStatus.CONFLICT,
                ops.begin(id, UUID.randomUUID(), "overworld", "nether", source("overworld")).status());
    }

    @Test
    void completedOutcomeIsReplayedWithinTtlAndNeverReExecuted() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        FakeServerConnection src = source("overworld");

        ops.begin(id, player, "overworld", "nether", src);
        ops.complete(id, TransferOutcome.failed(TransferError.BACKEND_DOWN, "target down"));

        TransferOperations.BeginResult replay = ops.begin(id, player, "overworld", "nether", src);
        assertEquals(TransferOperations.BeginStatus.REPLAY, replay.status());
        assertNotNull(replay.replay());
        assertFalse(replay.replay().ok());
        assertEquals(TransferError.BACKEND_DOWN, replay.replay().error());
        assertEquals(0, ops.pendingSize());
    }

    @Test
    void replayWindowExpiresAfterTtl() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ops.begin(id, player, "overworld", "nether", source("overworld"));
        ops.complete(id, TransferOutcome.ok("done"));

        clock.advance(Duration.ofMinutes(10).plusSeconds(1)); // DEFAULT_TTL is 10 minutes
        assertEquals(TransferOperations.BeginStatus.FRESH,
                ops.begin(id, player, "overworld", "nether", source("overworld")).status());
    }

    @Test
    void completedConflictIsRejected() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ops.begin(id, player, "overworld", "nether", source("overworld"));
        ops.complete(id, TransferOutcome.ok("done"));

        assertEquals(TransferOperations.BeginStatus.CONFLICT,
                ops.begin(id, player, "overworld", "end", source("overworld")).status());
    }

    @Test
    void abandonPlayerRemovesPendingAndCachesDisconnectedOutcome() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        FakeServerConnection src = source("overworld");
        ops.begin(id, player, "overworld", "nether", src);

        int abandoned = ops.abandonPlayer(player);
        assertEquals(1, abandoned);
        assertEquals(0, ops.pendingSize());
        assertEquals(1, ops.stats().abandonedOperations.get());

        // replay of the same request_id now resolves to DISCONNECTED
        TransferOperations.BeginResult replay = ops.begin(id, player, "overworld", "nether", src);
        assertEquals(TransferOperations.BeginStatus.REPLAY, replay.status());
        assertEquals(TransferError.DISCONNECTED, replay.replay().error());

        // ring contains the abandonment
        List<TransferOperations.RecentOperation> recent = ops.recent(10);
        assertEquals(1, recent.size());
        assertEquals(TransferOperations.OutcomeKind.ABANDONED, recent.get(0).kind());
    }

    @Test
    void completeAfterAbandonmentReturnsEmpty() {
        TransferOperations ops = newOperations();
        UUID id = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        ops.begin(id, player, "overworld", "nether", source("overworld"));
        ops.abandonPlayer(player);
        assertTrue(ops.complete(id, TransferOutcome.ok("late success")).isEmpty());
    }

    @Test
    void ringKeepsAtMostFiftyNewestFirst() {
        TransferOperations ops = newOperations();
        for (int i = 0; i < TransferOperations.RECENT_CAPACITY + 10; i++) {
            UUID id = UUID.randomUUID();
            UUID player = UUID.randomUUID();
            FakeServerConnection src = source("overworld");
            ops.begin(id, player, "overworld", "nether", src);
            ops.complete(id, TransferOutcome.ok("op " + i));
        }
        List<TransferOperations.RecentOperation> recent = ops.recent(100);
        assertEquals(TransferOperations.RECENT_CAPACITY, recent.size());
        for (int i = 0; i < recent.size() - 1; i++) {
            assertFalse(recent.get(i).finishedAt().isBefore(recent.get(i + 1).finishedAt()),
                    "recent operations must be ordered newest first");
        }
    }

    @Test
    void completedCacheDoesNotExceedCapacity() {
        TransferOperations ops = new TransferOperations(clock, TransferOperations.DEFAULT_TTL.toMillis(), 4, 8);
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            UUID player = UUID.randomUUID();
            ops.begin(id, player, "overworld", "nether", source("overworld"));
            ops.complete(id, TransferOutcome.ok("op " + i));
        }
        assertTrue(ops.completedSize() <= 8, "completed cache must be bounded, got " + ops.completedSize());
        assertTrue(ops.pendingSize() <= 4);
    }
}
