package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;
import kz.bejiihiu.dimensions.common.nether.PortalExitCalculator;
import kz.bejiihiu.dimensions.common.nether.PortalExitPlacement;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The exit placement selection the arrival pipeline relies on: hitbox-centered arrival at the
 * portal bottom, same-axis math (the source orientation does not survive the server switch),
 * yaw/pitch preservation and a zeroed velocity.
 */
class PortalExitMathTest {

    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    private PortalInnerRect xPortal() {
        return new PortalInnerRect(new BlockPos(100, 64, -30), PortalOrientation.X_AXIS, 2, 3);
    }

    private PortalInnerRect zPortal() {
        return new PortalInnerRect(new BlockPos(-7, 70, 40), PortalOrientation.Z_AXIS, 2, 3);
    }

    @Test
    void xAxisPortalCentersThePlayerOnTheWidthAxis() {
        Position ideal = new Position(0.0, 80.0, 0.0, 90.0, 10.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                xPortal(), PortalOrientation.X_AXIS, PortalOrientation.X_AXIS,
                new RelativePortalPosition(0.5, 0.0, 0.0), new Velocity(0.0, 0.0, 0.0),
                ideal.yaw(), ideal.pitch(), PLAYER_WIDTH, PLAYER_HEIGHT);
        assertEquals(101.0, placement.position().x());   // width 2 → center
        assertEquals(64.0, placement.position().y());    // bottom of the inner rectangle
        assertEquals(-29.5, placement.position().z());   // plane center (portal blocks at z=-30)
        assertEquals(90.0, placement.position().yaw());  // same axis → yaw preserved
        assertEquals(10.0, placement.position().pitch());
        assertEquals(0.0, placement.velocity().x());
        assertEquals(0.0, placement.velocity().y());
        assertEquals(0.0, placement.velocity().z());
    }

    @Test
    void zAxisPortalCentersThePlayerOnTheWidthAxis() {
        Position ideal = new Position(0.0, 90.0, 0.0, 225.0, -5.0);
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                zPortal(), PortalOrientation.Z_AXIS, PortalOrientation.Z_AXIS,
                new RelativePortalPosition(0.5, 0.0, 0.0), new Velocity(0.0, 0.0, 0.0),
                ideal.yaw(), ideal.pitch(), PLAYER_WIDTH, PLAYER_HEIGHT);
        assertEquals(-6.5, placement.position().x());    // plane center (portal blocks at x=-7)
        assertEquals(70.0, placement.position().y());
        assertEquals(41.0, placement.position().z());    // width 2 → center
        assertEquals(225.0, placement.position().yaw());
        assertEquals(-5.0, placement.position().pitch());
    }

    @Test
    void perpendicularExitRotatesYawAndVelocityExactlyLikeTheVanillaBytecode() {
        PortalExitPlacement placement = PortalExitCalculator.exitPlacement(
                xPortal(), PortalOrientation.X_AXIS, PortalOrientation.Z_AXIS,
                new RelativePortalPosition(0.5, 0.0, 0.0), new Velocity(1.0, 0.5, 0.0),
                30.0f, 0.0f, PLAYER_WIDTH, PLAYER_HEIGHT);
        assertEquals(120.0, placement.position().yaw(), 1e-6);
        assertEquals(0.0, placement.velocity().x(), 1e-6);   // (vx,vy,vz) → (vz,vy,-vx)
        assertEquals(0.5, placement.velocity().y(), 1e-6);
        assertEquals(-1.0, placement.velocity().z(), 1e-6);
    }
}
