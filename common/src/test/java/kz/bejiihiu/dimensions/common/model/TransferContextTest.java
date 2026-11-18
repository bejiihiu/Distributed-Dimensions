package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Invariants of the transfer-context row: fractions, axes and the toRelative round trip. */
class TransferContextTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private static TransferContext context(double fx, double fy, double forward,
                                           double width, double height) {
        return new TransferContext(UUID.randomUUID(), null, PortalOrientation.X_AXIS,
                fx, fy, forward, width, height, T0);
    }

    @Test
    void validRowRoundTripsToRelativePosition() {
        TransferContext row = new TransferContext(UUID.randomUUID(), UUID.randomUUID(),
                PortalOrientation.Z_AXIS, 0.25, 0.75, 0.0, 0.6, 1.8, T0);
        assertEquals(0.25, row.fx());
        assertEquals(0.75, row.fy());
        assertEquals(0.75, row.toRelative().up());
        assertEquals(0.25, row.toRelative().right());
        assertEquals(0.0, row.toRelative().forward());
        assertEquals(PortalOrientation.Z_AXIS, row.sourceAxis());
        assertEquals(T0, row.createdAt());
    }

    @Test
    void createdFactoryMapsRelativeComponents() {
        var relative = new kz.bejiihiu.dimensions.common.nether.RelativePortalPosition(0.1, 0.9, 0.0);
        UUID op = UUID.randomUUID();
        TransferContext row = TransferContext.created(op, null, PortalOrientation.X_AXIS,
                relative, 0.6, 1.8, T0);
        assertEquals(op, row.operationId());
        assertNull(row.playerUuid());
        assertEquals(0.1, row.fx());
        assertEquals(0.9, row.fy());
        assertEquals(0.0, row.forward());
        assertEquals(0.6, row.entityWidth());
        assertEquals(1.8, row.entityHeight());
    }

    @Test
    void fractionAboveOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> context(1.01, 0.5, 0.0, 0.6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 1.01, 0.0, 0.6, 1.8));
    }

    @Test
    void fractionBelowZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> context(-0.01, 0.5, 0.0, 0.6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, -0.01, 0.0, 0.6, 1.8));
    }

    @Test
    void nanAndInfiniteFractionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> context(Double.NaN, 0.5, 0.0, 0.6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, Double.NaN, 0.0, 0.6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(Double.POSITIVE_INFINITY, 0.5, 0.0, 0.6, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, Double.NEGATIVE_INFINITY, 0.0, 0.6, 1.8));
    }

    @Test
    void boundaryFractionsAreAccepted() {
        context(0.0, 0.0, 0.0, 0.6, 1.8);
        context(1.0, 1.0, -3.5, 0.0, 0.0);
    }

    @Test
    void nanForwardIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 0.5, Double.NaN, 0.6, 1.8));
    }

    @Test
    void negativeOrInfiniteHitboxIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 0.5, 0.0, -1.0, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 0.5, 0.0, 0.6, -0.5));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 0.5, 0.0, Double.NaN, 1.8));
        assertThrows(IllegalArgumentException.class, () -> context(0.5, 0.5, 0.0, 0.6, Double.POSITIVE_INFINITY));
    }

    @Test
    void missingAxisOrOperationIdIsRejected() {
        assertThrows(NullPointerException.class, () ->
                new TransferContext(UUID.randomUUID(), null, null, 0.5, 0.5, 0.0, 0.6, 1.8, T0));
        assertThrows(NullPointerException.class, () ->
                new TransferContext(null, null, PortalOrientation.X_AXIS, 0.5, 0.5, 0.0, 0.6, 1.8, T0));
    }
}
