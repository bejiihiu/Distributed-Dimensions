package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrivalClampTest {

    @Test
    void horizontalCoordinatesClampToBorderExtent() {
        Position out = ArrivalClamp.arrival(DimensionId.NETHER,
                new Position(100_000_000.0, 64.0, -100_000_000.0, 10.0, -20.0));
        assertEquals(ArrivalClamp.MAX_HORIZONTAL, out.x(), 0.0);
        assertEquals(-ArrivalClamp.MAX_HORIZONTAL, out.z(), 0.0);
        assertEquals(64.0, out.y(), 0.0);
        assertEquals(10.0, out.yaw(), 0.0);
        assertEquals(-20.0, out.pitch(), 0.0);
    }

    @Test
    void yClampsIntoBuildHeight() {
        Position low = ArrivalClamp.arrival(DimensionId.OVERWORLD, new Position(0, -10_000, 0));
        Position high = ArrivalClamp.arrival(DimensionId.OVERWORLD, new Position(0, 100_000, 0));
        assertEquals(ArrivalClamp.MIN_Y, low.y(), 0.0);
        assertEquals(ArrivalClamp.MAX_Y, high.y(), 0.0);
    }

    @Test
    void boundaryValuesAreKept() {
        Position out = ArrivalClamp.arrival(DimensionId.OVERWORLD,
                new Position(ArrivalClamp.MAX_HORIZONTAL, 319.0, -ArrivalClamp.MAX_HORIZONTAL));
        assertEquals(ArrivalClamp.MAX_HORIZONTAL, out.x(), 0.0);
        assertEquals(319.0, out.y(), 0.0);
        assertEquals(-ArrivalClamp.MAX_HORIZONTAL, out.z(), 0.0);
    }

    @Test
    void endArrivalCollapsesToSpawnIslandAnchorPreservingRotation() {
        Position out = ArrivalClamp.arrival(DimensionId.THE_END,
                new Position(12345.6, 123.0, -9876.5, 45.0, -30.0));
        assertEquals(ArrivalClamp.END_SPAWN_X, out.x(), 0.0);
        assertEquals(ArrivalClamp.END_SPAWN_Y, out.y(), 0.0);
        assertEquals(ArrivalClamp.END_SPAWN_Z, out.z(), 0.0);
        assertEquals(45.0, out.yaw(), 0.0);
        assertEquals(-30.0, out.pitch(), 0.0);
    }

    @Test
    void nanHorizontalClampsToZero() {
        assertEquals(0.0, ArrivalClamp.clampHorizontal(Double.NaN), 0.0);
        assertEquals(ArrivalClamp.MAX_HORIZONTAL, ArrivalClamp.clampHorizontal(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(-ArrivalClamp.MAX_HORIZONTAL, ArrivalClamp.clampHorizontal(Double.NEGATIVE_INFINITY), 0.0);
    }

    @Test
    void nanYClampsToMin() {
        assertEquals(ArrivalClamp.MIN_Y, ArrivalClamp.clampY(Double.NaN), 0.0);
        assertEquals(ArrivalClamp.MIN_Y, ArrivalClamp.clampY(Double.NEGATIVE_INFINITY), 0.0);
    }

    @Test
    void netherArrivalDoesNotScaleLikeAPortal() {
        // 1:8 portal scaling must NOT be applied — a plugin teleport keeps the coordinates
        Position out = ArrivalClamp.arrival(DimensionId.NETHER, new Position(800.0, 70.0, 1600.0));
        assertEquals(800.0, out.x(), 0.0);
        assertEquals(1600.0, out.z(), 0.0);
    }

    @Test
    void endArrivalForRespawnLikeCausesAlsoUsesIslandFallback() {
        // any cause landing in the End lands on the island — the mirage can carry no terrain hint
        Position out = ArrivalClamp.arrival(DimensionId.THE_END, new Position(500_000.0, -64.0, 500_000.0));
        assertTrue(out.y() >= 60.0, "end arrival must never drop the player into the void");
        assertEquals(ArrivalClamp.END_SPAWN_X, out.x(), 0.0);
    }
}
