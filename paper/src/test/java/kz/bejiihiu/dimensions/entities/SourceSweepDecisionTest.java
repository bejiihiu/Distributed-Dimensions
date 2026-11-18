package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceSweepDecisionTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);
    private static final long TIMEOUT_MILLIS = 10 * 60 * 1_000L;

    private static EntityTransferPayload row(int seq, EntityPayloadState state) {
        return new EntityTransferPayload(UUID.randomUUID().toString(), seq, EntityPayloadKind.MOB,
                "nether", "minecraft:nether", null,
                "{}", state, T0, T0);
    }

    @Test
    void noRowsMeansRetire() {
        assertEquals(SourceSweepDecision.Action.RETIRE,
                SourceSweepDecision.decide(List.of(), T0.toEpochMilli(), T0.toEpochMilli(), TIMEOUT_MILLIS).action());
    }

    @Test
    void anyFailedRowMeansRetract() {
        List<EntityTransferPayload> rows = List.of(
                row(0, EntityPayloadState.CLAIMED),
                row(1, EntityPayloadState.FAILED),
                row(2, EntityPayloadState.PENDING));
        assertEquals(SourceSweepDecision.Action.RETRACT,
                SourceSweepDecision.decide(rows, T0.toEpochMilli(), T0.toEpochMilli(), TIMEOUT_MILLIS).action());
    }

    @Test
    void pendingRowsWithinTimeoutWait() {
        List<EntityTransferPayload> rows = List.of(row(0, EntityPayloadState.PENDING));
        long now = T0.toEpochMilli() + 1_000;
        assertEquals(SourceSweepDecision.Action.WAIT,
                SourceSweepDecision.decide(rows, T0.toEpochMilli(), now, TIMEOUT_MILLIS).action());
    }

    @Test
    void claimedRowsWithinTimeoutWait() {
        List<EntityTransferPayload> rows = List.of(
                row(0, EntityPayloadState.CLAIMED),
                row(1, EntityPayloadState.CLAIMED));
        long now = T0.toEpochMilli() + TIMEOUT_MILLIS - 1;
        assertEquals(SourceSweepDecision.Action.WAIT,
                SourceSweepDecision.decide(rows, T0.toEpochMilli(), now, TIMEOUT_MILLIS).action());
    }

    @Test
    void expiredAckTimeoutMeansExpireRegardlessOfRowState() {
        List<EntityTransferPayload> rows = List.of(
                row(0, EntityPayloadState.CLAIMED));
        long now = T0.toEpochMilli() + TIMEOUT_MILLIS;
        assertEquals(SourceSweepDecision.Action.EXPIRE,
                SourceSweepDecision.decide(rows, T0.toEpochMilli(), now, TIMEOUT_MILLIS).action());
    }

    @Test
    void failedRowsWinOverExpire() {
        List<EntityTransferPayload> rows = List.of(row(0, EntityPayloadState.FAILED));
        long now = T0.toEpochMilli() + TIMEOUT_MILLIS + 1_000;
        assertEquals(SourceSweepDecision.Action.RETRACT,
                SourceSweepDecision.decide(rows, T0.toEpochMilli(), now, TIMEOUT_MILLIS).action());
    }
}
