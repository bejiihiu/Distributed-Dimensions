package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalExitCalculatorTest {

    private static final PortalInnerRect SOURCE = new PortalInnerRect(new BlockPos(10, 64, 20), PortalOrientation.X_AXIS, 2, 3);
    private static final PortalInnerRect TARGET_SAME = new PortalInnerRect(new BlockPos(100, 30, 200), PortalOrientation.X_AXIS, 2, 3);
    private static final PortalInnerRect TARGET_ROTATED = new PortalInnerRect(new BlockPos(100, 30, 200), PortalOrientation.Z_AXIS, 2, 3);

    private static RelativePortalPosition rel(PortalInnerRect rect, PortalOrientation axis, Position p) {
        // vanilla Reference: player 0.6 wide, 1.8 tall
        return PortalExitCalculator.relativePosition(rect, axis, p, 0.6, 1.8, true);
    }

    @Test
    void centeredInSourceMapsToCenterOfTarget() {
        // exact center of a 2x3 inner portal with a 0.6-wide player: mid width = lowerLeft + width/2
        Position centered = new Position(11.0, 64.0, 20.5, 0.0, 0.0);
        RelativePortalPosition r = rel(SOURCE, PortalOrientation.X_AXIS, centered);
        // roomWidth = 2 - 0.6 = 1.4; right = (11.0 - (10 + 0.3)) / 1.4 = 0.7/1.4 = 0.5
        assertEquals(0.5, r.right(), 1e-10);
        // roomHeight = 3 - 1.8 = 1.2; up = (64.0 - 64) / 1.2 = 0
        assertEquals(0.0, r.up(), 1e-10);

        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 45.0, 10.0, 0.6, 1.8);
        assertEquals(100 + 0.3 + 1.4 * 0.5, placement.position().x(), 1e-10);
        assertEquals(30.0, placement.position().y(), 1e-10);
        assertEquals(200.5, placement.position().z(), 1e-10);
        assertEquals(45.0, placement.position().yaw(), 1e-10);
        assertEquals(10.0, placement.position().pitch(), 1e-10);
    }

    @Test
    void leftEdgeHugsLeftInTarget() {
        Position hugging = new Position(10.3, 64.0, 20.5, 0.0, 0.0);
        RelativePortalPosition r = rel(SOURCE, PortalOrientation.X_AXIS, hugging);
        assertEquals(0.0, r.right(), 1e-10);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 270.0, -5.0, 0.6, 1.8);
        assertEquals(100.3, placement.position().x(), 1e-10);
        assertEquals(-5.0, placement.position().pitch(), 1e-10);
    }

    @Test
    void topEdgeMapsToTopOfTarget() {
        Position top = new Position(11.0, 64.0 + 1.2, 20.5, 0.0, 0.0);
        RelativePortalPosition r = rel(SOURCE, PortalOrientation.X_AXIS, top);
        assertEquals(1.0, r.up(), 1e-10);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 0.0, 0.0, 0.6, 1.8);
        assertEquals(30.0 + 1.2, placement.position().y(), 1e-10);
    }

    @Test
    void entityWiderThanPortalFallsBackToBottomMiddle() {
        // a 4-wide entity can never be placed by ratio in a 2-wide portal -> right = 0.5
        RelativePortalPosition r = PortalExitCalculator.relativePosition(
                SOURCE, PortalOrientation.X_AXIS, new Position(10.0, 64.0, 20.5), 4.0, 5.0, true);
        assertEquals(0.5, r.right(), 1e-10);
        assertEquals(0.0, r.up(), 1e-10); // taller than the portal -> bottom
    }

    @Test
    void entityTallerThanPortalButFitsWidth() {
        RelativePortalPosition r = PortalExitCalculator.relativePosition(
                SOURCE, PortalOrientation.X_AXIS, new Position(10.0, 64.0, 20.5), 0.6, 3.0, true);
        assertEquals(0.0, r.up(), 1e-10);
        assertTrue(r.right() >= 0.0 && r.right() <= 1.0);
    }

    @Test
    void perpendicularPortalsIncreaseYawBy90AndKeepPitch() {
        RelativePortalPosition r = new RelativePortalPosition(0.7, 0.3, 0.25);
        PortalExitPlacement same = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, new Velocity(0.0, 0.5, 1.0), 123.0, 22.0, 0.6, 1.8);
        assertEquals(123.0, same.position().yaw(), 1e-10);
        assertEquals(22.0, same.position().pitch(), 1e-10);
        assertEquals(0.0, new Velocity(0.0, 0.5, 1.0).lengthSquared() - same.velocity().lengthSquared(), 1e-10);
        assertEquals(1.0, same.velocity().z(), 1e-10);

        PortalExitPlacement rotated = PortalExitCalculator.exitPlacement(
                TARGET_ROTATED, PortalOrientation.Z_AXIS, PortalOrientation.X_AXIS,
                r, new Velocity(0.0, 0.5, 1.0), 123.0, 22.0, 0.6, 1.8);
        // yaw increases by exactly 90 (vanilla: unconditional, regardless of travel direction)
        assertEquals(213.0, rotated.position().yaw(), 1e-10);
        assertEquals(22.0, rotated.position().pitch(), 1e-10);
        assertEquals(90.0, rotated.yawRotationDegrees(), 1e-10);
        // velocity: (vx, vy, vz) -> (vz, vy, -vx) — the decompiled 1.21 behavior
        assertEquals(1.0, rotated.velocity().x(), 1e-10);
        assertEquals(0.5, rotated.velocity().y(), 1e-10);
        assertEquals(0.0, rotated.velocity().z(), 1e-10);
    }

    @Test
    void velocityRotationCases() {
        assertVelocityAfterRotation(0.0, 0.0, 1.0, 1.0, 0.0, 0.0);   // south -> east
        assertVelocityAfterRotation(1.0, 0.0, 0.0, 0.0, 0.0, -1.0);   // east -> north
        assertVelocityAfterRotation(0.0, 0.0, -1.0, -1.0, 0.0, 0.0);  // north -> west
        assertVelocityAfterRotation(-1.0, 0.0, 0.0, 0.0, 0.0, 1.0);   // west -> south
        assertVelocityAfterRotation(0.5, -0.25, 2.0, 2.0, -0.25, -0.5);
    }

    private void assertVelocityAfterRotation(double vx, double vy, double vz, double ex, double ey, double ez) {
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_ROTATED, PortalOrientation.Z_AXIS, PortalOrientation.X_AXIS,
                r, new Velocity(vx, vy, vz), 0.0, 0.0, 0.0, 0.0);
        assertEquals(ex, placement.velocity().x(), 1e-10);
        assertEquals(ey, placement.velocity().y(), 1e-10);
        assertEquals(ez, placement.velocity().z(), 1e-10);
    }

    @Test
    void sameAxisKeepsVelocityUnchanged() {
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        Velocity input = new Velocity(3.0, -1.0, 4.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, input, 0.0, 0.0, 0.0, 0.0);
        assertEquals(input, placement.velocity());
    }

    @Test
    void positionMappingSwapsWidthAndForwardForRotatedTarget() {
        // right = 0.75, up = 0.5, forward = 0.25 into a rotated (Z_AXIS) exit:
        // width axis becomes Z, forward (0.5+0.25=0.75) becomes X offset
        RelativePortalPosition r = new RelativePortalPosition(0.75, 0.5, 0.25);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_ROTATED, PortalOrientation.Z_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 0.0, 0.0, 0.6, 1.8);
        // halfWidth = 0.3 + (2 - 0.6) * 0.75 = 0.3 + 1.05 = 1.35
        // upOffset = (3 - 1.8) * 0.5 = 0.6; forwardOffset = 0.75
        assertEquals(100.0 + 0.75, placement.position().x(), 1e-10);
        assertEquals(30.0 + 0.6, placement.position().y(), 1e-10);
        assertEquals(200.0 + 1.35, placement.position().z(), 1e-10);
    }

    @Test
    void livingEntitiesDropForwardOffset() {
        RelativePortalPosition living = PortalExitCalculator.relativePositionLivingEntity(
                SOURCE, PortalOrientation.X_AXIS, new Position(11.0, 64.0, 20.9), 0.6, 1.8);
        assertEquals(0.0, living.forward(), 1e-10);
        // item entities keep it
        RelativePortalPosition item = PortalExitCalculator.relativePosition(
                SOURCE, PortalOrientation.X_AXIS, new Position(11.0, 64.0, 20.9), 0.25, 0.25, false);
        assertEquals(20.9 - 20.5, item.forward(), 1e-10);
    }

    @Test
    void forwardOffsetCentersEntityInPlaneWhenZero() {
        // zero forward -> exits exactly centered in the portal plane
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_SAME, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 0.0, 0.0, 0.6, 1.8);
        assertEquals(200.5, placement.position().z(), 1e-10);
    }

    @Test
    void roundTripThroughIdenticalPortals() {
        PortalInnerRect exit = new PortalInnerRect(new BlockPos(-50, 100, -60), PortalOrientation.X_AXIS, 2, 3);
        Position entity = new Position(10.9, 65.0, 20.4, 137.0, 20.0);
        PortalExitPlacement first = PortalExitCalculator.computeExit(
                SOURCE, PortalOrientation.X_AXIS, entity,
                exit, PortalOrientation.X_AXIS,
                new Velocity(0.0, 1.5, 0.0), 137.0, 20.0,
                0.6, 1.8, true);
        // reverse: from the exit portal back into a same-sized source-shaped rect
        PortalExitPlacement back = PortalExitCalculator.computeExit(
                exit, PortalOrientation.X_AXIS, first.position(),
                SOURCE, PortalOrientation.X_AXIS,
                first.velocity(), first.position().yaw(), first.position().pitch(),
                0.6, 1.8, true);
        assertEquals(entity.x(), back.position().x(), 1e-9);
        assertEquals(entity.y(), back.position().y(), 1e-9);
        assertEquals(137.0, back.position().yaw(), 1e-9);
    }

    @Test
    void largerFrameScalesOffsetsByRatio() {
        // entity 1/4 across a 2x3 into a 4x5: lands at 1/4 across the 4x5
        PortalInnerRect bigTarget = new PortalInnerRect(new BlockPos(200, 30, 200), PortalOrientation.X_AXIS, 4, 5);
        RelativePortalPosition r = new RelativePortalPosition(0.25, 0.5, 0.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                bigTarget, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 0.0, 0.0, 0.6, 1.8);
        double halfWidth = 0.3 + (4 - 0.6) * 0.25;
        assertEquals(200.0 + halfWidth, placement.position().x(), 1e-10);
        double upOffset = (5 - 1.8) * 0.5;
        assertEquals(30.0 + upOffset, placement.position().y(), 1e-10);
    }

    @Test
    void yawWrapsAround360() {
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_ROTATED, PortalOrientation.Z_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, 350.0, 0.0, 0.6, 1.8);
        assertEquals(80.0, placement.position().yaw(), 1e-10);
    }

    @Test
    void negativeYawWraps() {
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                TARGET_ROTATED, PortalOrientation.Z_AXIS, PortalOrientation.X_AXIS,
                r, Velocity.ZERO, -30.0, 0.0, 0.6, 1.8);
        assertEquals(60.0, placement.position().yaw(), 1e-10);
    }

    @Test
    void zAxisSourceMapsCorrectly() {
        PortalInnerRect zSource = new PortalInnerRect(new BlockPos(0, 64, 0), PortalOrientation.Z_AXIS, 2, 3);
        Position entity = new Position(0.3, 64.6, 0.75, 0.0, 0.0);
        RelativePortalPosition r = PortalExitCalculator.relativePosition(zSource, PortalOrientation.Z_AXIS, entity, 0.6, 1.8, true);
        // start = 0 + 0.3; roomWidth = 1.4; right = (0.75 - 0.3)/1.4
        assertEquals(0.45 / 1.4, r.right(), 1e-10);
        assertEquals((64.6 - 64) / 1.2, r.up(), 1e-10);
        // living entities zero the forward offset; item entities keep it
        assertEquals(0.0, r.forward(), 1e-10);
        RelativePortalPosition item = PortalExitCalculator.relativePosition(zSource, PortalOrientation.Z_AXIS, entity, 0.6, 1.8, false);
        // forward measured along X: entity x - (x0 + 0.5) = 0.3 - 0.5 = -0.2
        assertEquals(-0.2, item.forward(), 1e-10);
    }

    @Test
    void invalidInputRejected() {
        RelativePortalPosition r = new RelativePortalPosition(0.5, 0.5, 0.0);
        assertThrows(NullPointerException.class, () -> PortalExitCalculator.exitPlacement(
                null, PortalOrientation.X_AXIS, PortalOrientation.X_AXIS, r, Velocity.ZERO, 0, 0, 0.6, 1.8));
        assertThrows(NullPointerException.class, () -> PortalExitCalculator.relativePosition(
                null, PortalOrientation.X_AXIS, new Position(0, 64, 0), 0.6, 1.8, false));
        assertThrows(IllegalArgumentException.class, () ->
                new RelativePortalPosition(Double.NaN, 0.5, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                new PortalExitPlacement(new Position(0, 64, 0), Velocity.ZERO, Double.POSITIVE_INFINITY));
    }
}
