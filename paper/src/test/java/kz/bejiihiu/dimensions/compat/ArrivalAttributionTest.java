package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.model.TransferError;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalAttributionTest {

    private static TransferOperation row(UUID player, DimensionId target, TransferCause cause,
                                         OperationState state, Instant updated) {
        TransferError error = state == OperationState.FAILED ? TransferError.TIMEOUT : null;
        String errorMessage = state == OperationState.FAILED ? "timeout" : null;
        return new TransferOperation(UUID.randomUUID(), player,
                "source", "target", DimensionId.OVERWORLD, target,
                cause, new Position(0, 64, 0), state,
                updated.minusSeconds(1), updated, error, errorMessage);
    }

    @Test
    void picksFreshRowForPlayerAndJoinedDimension() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        Instant fresh = now.minusSeconds(10);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.NETHER, TransferCause.MIRAGE_TELEPORT, OperationState.COMMITTED, fresh));

        Optional<TransferOperation> selected = ArrivalAttribution.select(
                rows, player, DimensionId.NETHER, now);
        assertTrue(selected.isPresent());
        assertEquals(TransferCause.MIRAGE_TELEPORT, selected.get().cause());
    }

    @Test
    void ignoresRowsForOtherDimensionsAndOtherPlayers() {
        UUID player = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Instant now = Instant.now();
        Instant fresh = now.minusSeconds(10);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.THE_END, TransferCause.MIRAGE_TELEPORT, OperationState.COMMITTED, fresh),
                row(stranger, DimensionId.NETHER, TransferCause.MIRAGE_TELEPORT, OperationState.COMMITTED, fresh));

        assertTrue(ArrivalAttribution.select(rows, player, DimensionId.NETHER, now).isEmpty());
    }

    @Test
    void ignoresFailedRows() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        Instant fresh = now.minusSeconds(10);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.NETHER, TransferCause.NETHER_PORTAL, OperationState.FAILED, fresh));

        assertTrue(ArrivalAttribution.select(rows, player, DimensionId.NETHER, now).isEmpty());
    }

    @Test
    void ignoresStaleRowsOutsideTheWindow() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        Instant stale = now.minusMillis(ArrivalAttribution.WINDOW_MILLIS + 60_000);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.NETHER, TransferCause.NETHER_PORTAL, OperationState.COMMITTED, stale));

        assertTrue(ArrivalAttribution.select(rows, player, DimensionId.NETHER, now).isEmpty());
    }

    @Test
    void picksTheNewestFreshRowWhenSeveralExist() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        Instant older = now.minusSeconds(60);
        Instant newer = now.minusSeconds(5);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.NETHER, TransferCause.PROGRAMMATIC, OperationState.COMMITTED, older),
                row(player, DimensionId.NETHER, TransferCause.NETHER_PORTAL, OperationState.COMMITTED, newer));

        Optional<TransferOperation> selected = ArrivalAttribution.select(
                rows, player, DimensionId.NETHER, now);
        assertTrue(selected.isPresent());
        assertEquals(TransferCause.NETHER_PORTAL, selected.get().cause());
    }

    @Test
    void toleratesSmallClockSkewAhead() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        Instant slightlyAhead = now.plusSeconds(10);
        List<TransferOperation> rows = List.of(
                row(player, DimensionId.NETHER, TransferCause.NETHER_PORTAL, OperationState.COMMITTED, slightlyAhead));

        assertTrue(ArrivalAttribution.select(rows, player, DimensionId.NETHER, now).isPresent());
    }

    @Test
    void environmentMappingUsesCanonicalDimensionsAndRejectsCustom() {
        assertEquals(Optional.of(DimensionId.OVERWORLD), ArrivalAttribution.dimensionFor(World.Environment.NORMAL));
        assertEquals(Optional.of(DimensionId.NETHER), ArrivalAttribution.dimensionFor(World.Environment.NETHER));
        assertEquals(Optional.of(DimensionId.THE_END), ArrivalAttribution.dimensionFor(World.Environment.THE_END));
        assertTrue(ArrivalAttribution.dimensionFor(World.Environment.CUSTOM).isEmpty());
        assertTrue(ArrivalAttribution.dimensionFor(null).isEmpty());
    }
}
