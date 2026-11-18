package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import kz.bejiihiu.dimensions.common.nether.PortalExitPlacement;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.TransferSourceContext;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The source-relative placement math the player path rewires: fractions captured inside the
 * source portal are re-mapped onto the destination rectangle by {@code PortalExitCalculator}
 * (vanilla 1:1), with the source axis travelling alongside so perpendicular portals rotate
 * yaw by 90° (the bytecode rule).
 */
class NetherExitPlacementTest {

    private static final Instant T0 = Instant.ofEpochMilli(1_800_000_000_000L);

    private static PortalInnerRect xPortal() {
        return new PortalInnerRect(new BlockPos(100, 64, -30), PortalOrientation.X_AXIS, 2, 3);
    }

    private static PortalInnerRect zPortal() {
        return new PortalInnerRect(new BlockPos(-7, 70, 40), PortalOrientation.Z_AXIS, 2, 3);
    }

    private static PortalInnerRect sourceX() {
        return new PortalInnerRect(new BlockPos(10, 50, 20), PortalOrientation.X_AXIS, 2, 3);
    }

    private static PortalInnerRect sourceZ() {
        return new PortalInnerRect(new BlockPos(30, 40, -15), PortalOrientation.Z_AXIS, 2, 3);
    }

    private static Position ideal(double yaw, double pitch) {
        return new Position(0.0, 80.0, 0.0, yaw, pitch);
    }

    private static TransferContext context(PortalInnerRect source, double right, double up,
                                           double width, double height) {
        RelativePortalPosition relative = new RelativePortalPosition(right, up, 0.0);
        return TransferContext.created(UUID.randomUUID(), UUID.randomUUID(), source.axis(),
                relative, width, height, T0);
    }

    // ------------------------------------------------------- source fractions

    @Test
    void centeredPlayerGetsCenteredFractions() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(11.0, 50.6, 20.25, 90.0, 0.0), 0.6, 1.8);
        assertEquals(0.5, ctx.relative().right(), 1e-9);
        assertEquals(0.5, ctx.relative().up(), 1e-9);   // hitbox bottom at the rect's middle band
        assertEquals(0.0, ctx.relative().forward(), "players are living entities: forward is zero");
        assertEquals(PortalOrientation.X_AXIS, ctx.axis());
        assertEquals(0.6, ctx.entityWidth());
        assertEquals(1.8, ctx.entityHeight());
    }

    @Test
    void hitboxHuggingTheStartEdgeGetsZeroFractions() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(10.3, 50.0, 20.25, 0.0, 0.0), 0.6, 1.8);
        assertEquals(0.0, ctx.relative().right(), 1e-9);
        assertEquals(0.0, ctx.relative().up(), 1e-9);
    }

    @Test
    void hitboxHuggingTheEndEdgeGetsOneFractions() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(11.7, 51.2, 20.25, 0.0, 0.0), 0.6, 1.8);
        assertEquals(1.0, ctx.relative().right(), 1e-9);
        assertEquals(1.0, ctx.relative().up(), 1e-9);
    }

    @Test
    void fractionsClampForPositionsPartiallyOutsideTheRect() {
        TransferSourceContext below = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(9.0, 48.0, 20.25, 0.0, 0.0), 0.6, 1.8);
        assertEquals(0.0, below.relative().right(), 1e-9);
        assertEquals(0.0, below.relative().up(), 1e-9);
        TransferSourceContext above = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(15.0, 60.0, 20.25, 0.0, 0.0), 0.6, 1.8);
        assertEquals(1.0, above.relative().right(), 1e-9);
        assertEquals(1.0, above.relative().up(), 1e-9);
    }

    @Test
    void hitboxWiderThanThePortalFallsBackToHalf() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(11.0, 51.0, 20.25, 0.0, 0.0), 4.0, 1.8);
        assertEquals(0.5, ctx.relative().right(), 1e-9);
        // a 4-block-wide hitbox in a 2-wide portal: centered, not left-leaning
        TransferSourceContext tall = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(11.0, 51.0, 20.25, 0.0, 0.0), 0.6, 4.0);
        assertEquals(0.0, tall.relative().up(), 1e-9);
    }

    @Test
    void zAxisSourceFractionsRunAlongZ() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceZ(), new Position(30.25, 41.2, -13.3, 0.0, 0.0), 0.6, 1.8);
        assertEquals(1.0, ctx.relative().right(), 1e-9);
        assertEquals(1.0, ctx.relative().up(), 1e-9);
        assertEquals(PortalOrientation.Z_AXIS, ctx.axis());
    }

    @Test
    void crouchingHeightIsCarriedIntoTheContext() {
        TransferSourceContext ctx = NetherPortalListener.sourceContextOf(
                sourceX(), new Position(11.0, 50.75, 20.25, 0.0, 0.0), 0.6, 1.5);
        assertEquals(1.5, ctx.entityHeight());
        assertEquals(0.5, ctx.relative().up(), 1e-9);
    }

    // ------------------------------------------------------- exit re-mapping

    @Test
    void contextFractionsAreRemappedOntoTheSameAxisExit() {
        PortalInnerRect source = sourceX();
        PortalInnerRect exit = xPortal();
        TransferContext ctx = context(source, 0.0, 0.0, 0.6, 1.8);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(90.0, 10.0), Optional.of(ctx));
        assertEquals(100.3, placement.position().x(), 1e-9);   // hugging the start edge
        assertEquals(64.0, placement.position().y(), 1e-9);    // bottom
        assertEquals(-29.5, placement.position().z(), 1e-9);   // plane center, forward=0
        assertEquals(90.0, placement.position().yaw(), 1e-9);  // same axis → yaw kept
        assertEquals(10.0, placement.position().pitch(), 1e-9);
        assertEquals(0.0, placement.velocity().x());
        assertEquals(0.0, placement.velocity().y());
        assertEquals(0.0, placement.velocity().z());

        TransferContext topRight = context(source, 1.0, 1.0, 0.6, 1.8);
        PortalExitPlacement far =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(90.0, 10.0), Optional.of(topRight));
        assertEquals(101.7, far.position().x(), 1e-9);
        assertEquals(65.2, far.position().y(), 1e-9);
    }

    @Test
    void contextFractionsRemapAcrossZAcrossTheWidthAxis() {
        PortalInnerRect exit = zPortal();
        TransferContext ctx = context(sourceZ(), 1.0, 1.0, 0.6, 1.8);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(200.0, 0.0), Optional.of(ctx));
        assertEquals(-6.5, placement.position().x(), 1e-9);    // plane center
        assertEquals(71.2, placement.position().y(), 1e-9);
        assertEquals(41.7, placement.position().z(), 1e-9);    // hugging the end edge
        assertEquals(200.0, placement.position().yaw(), 1e-9);
    }

    @Test
    void perpendicularExitRotatesYawByNinetyAndKeepsZeroVelocity() {
        PortalInnerRect exit = zPortal();
        TransferContext ctx = context(sourceX(), 0.5, 0.5, 0.6, 1.8);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(350.0, 20.0), Optional.of(ctx));
        assertEquals(80.0, placement.position().yaw(), 1e-9);   // 350 + 90, normalized
        assertEquals(20.0, placement.position().pitch(), 1e-9);
        // the 90° velocity map is (vz, vy, -vx) — a zero x-velocity comes out as -0.0, which
        // is the bytecode-faithful value; compare with a delta, sign of zero is meaningless here
        assertEquals(0.0, placement.velocity().x(), 1e-9);
        assertEquals(0.0, placement.velocity().y(), 1e-9);
        assertEquals(0.0, placement.velocity().z(), 1e-9);
        // fx now spans Z in a Z-axis exit; x is the plane coordinate
        assertEquals(-6.5, placement.position().x(), 1e-9);
        assertEquals(41.0, placement.position().z(), 1e-9);
    }

    @Test
    void perpendicularExitOfOppositeDirectionXIntoXM90() {
        // vanilla always adds 90°, never subtracts, regardless of direction of travel (MC-198623)
        PortalInnerRect exit = xPortal();
        TransferContext ctx = context(sourceZ(), 0.5, 0.5, 0.6, 1.8);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(90.0, 0.0), Optional.of(ctx));
        assertEquals(180.0, placement.position().yaw(), 1e-9);
    }

    @Test
    void missingContextFallsBackToBottomMiddleSharedAxisMath() {
        PortalInnerRect exit = xPortal();
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(123.0, 45.0), Optional.empty());
        assertEquals(101.0, placement.position().x(), 1e-9);
        assertEquals(64.0, placement.position().y(), 1e-9);
        assertEquals(-29.5, placement.position().z(), 1e-9);
        assertEquals(123.0, placement.position().yaw(), 1e-9);
        assertEquals(45.0, placement.position().pitch(), 1e-9);
    }

    @Test
    void contextCarriedHitboxIsUsedForTheDestinationMapping() {
        // a crouching player (1.5 high) arriving into a 3-block-tall portal:
        // the up fraction maps against (3 - 1.5), not (3 - 1.8)
        PortalInnerRect source = sourceX();
        PortalInnerRect exit = xPortal();
        TransferContext crouched = context(source, 0.5, 1.0, 0.6, 1.5);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(0.0, 0.0), Optional.of(crouched));
        assertEquals(65.5, placement.position().y(), 1e-9);   // 64 + (3 - 1.5) * 1.0
    }

    @Test
    void hitboxLargerThanTheTargetPortalMapsOutOfBoundsLikeVanilla() {
        // the 0.5 fallback happens on the source (hitbox > source width); on the target the
        // same linear map is applied to the reduced rectangle, exactly as vanilla does
        PortalInnerRect exit = new PortalInnerRect(new BlockPos(100, 64, -30), PortalOrientation.X_AXIS, 1, 3);
        TransferContext ctx = new TransferContext(UUID.randomUUID(), null, PortalOrientation.X_AXIS,
                0.8, 0.5, 0.0, 4.0, 1.8, T0);
        PortalExitPlacement placement =
                NetherArrivalExecutor.exitPlacementFromContext(exit, ideal(0.0, 0.0), Optional.of(ctx));
        // 0.6/2 = 2.0... halfWidth = 2.0 + (1 - 4.0) * 0.8 = -0.4 → outside the rect, vanilla-consistent
        assertEquals(99.6, placement.position().x(), 1e-9);
    }
}
