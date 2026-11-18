package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;
import kz.bejiihiu.dimensions.common.util.PortalMathPrimitives;

/**
 * Entity placement math for portal transfers (vanilla {@code NetherPortalBlock.createTeleportTarget}
 * + {@code NetherPortal.entityPosInPortal} in the decompiled 1.21.x sources).
 *
 * <p>Rules reproduced:
 * <ul>
 *   <li>Both portals' inner rectangles are reduced by the entity's hitbox (width and height),
 *       leaving the entity as a point; the right/up offsets are measured in the reduced rectangle
 *       and re-mapped onto the destination rectangle by ratio — this is what preserves an
 *       entity's relative spot between differently-sized frames.</li>
 *   <li>If the entity is wider than a portal in a dimension, that dimension falls back to the
 *       center (horizontally) / bottom (vertically); the other dimension still follows the ratio.</li>
 *   <li>If the destination portal is rotated 90° relative to the source, yaw increases by 90°
 *       (unconditionally, regardless of direction of travel) and the velocity is transformed as
 *       {@code (vx, vy, vz) -> (vz, vy, -vx)} — this is what the 1.21.x bytecode applies; it is
 *       NOT the same rotation handedness as the yaw delta. The pitch is never changed.</li>
 *   <li>Position re-mapping: when both portals share an axis, the forward offset survives as-is;
 *       perpendicular portals swap its contribution into the other horizontal axis.</li>
 * </ul>
 *
 * <p>Living entities, minecarts and boats drop the forward offset before mapping (vanilla
 * {@code LivingEntity.positionInPortal} zeroes the z component); other entities keep it.
 * Use {@link #relativePosition(PortalInnerRect, PortalOrientation, Position, double, double, boolean)}
 * with {@code zeroForward} as appropriate.
 */
public final class PortalExitCalculator {

    private PortalExitCalculator() {
    }

    /**
     * Computes the relative position of an entity inside a portal rectangle.
     *
     * @param zeroForward true for living entities, minecarts and boats (drops the forward offset)
     */
    public static RelativePortalPosition relativePosition(
            PortalInnerRect source, PortalOrientation sourceAxis, Position entityPosition,
            double entityWidth, double entityHeight, boolean zeroForward) {
        if (source == null || sourceAxis == null || entityPosition == null) {
            throw new NullPointerException("source, sourceAxis and entityPosition must not be null");
        }
        if (!(entityWidth >= 0.0) || !(entityHeight >= 0.0)) {
            throw new IllegalArgumentException("entity dimensions must be >= 0");
        }

        double roomWidth = source.width() - entityWidth;
        double roomHeight = source.height() - entityHeight;

        double right;
        if (roomWidth > 0.0) {
            double start = (sourceAxis == PortalOrientation.X_AXIS ? source.lowerLeft().x() : source.lowerLeft().z())
                    + entityWidth / 2.0;
            right = clamp01((alongAxis(entityPosition, sourceAxis) - start) / roomWidth);
        } else {
            right = 0.5;
        }

        double up;
        if (roomHeight > 0.0) {
            up = clamp01((entityPosition.y() - source.lowerLeft().y()) / roomHeight);
        } else {
            up = 0.0;
        }

        double forward = perpendicularAxis(entityPosition, sourceAxis) - (perpendicularCoord(source.lowerLeft(), sourceAxis) + 0.5);
        if (zeroForward) {
            forward = 0.0;
        }
        return new RelativePortalPosition(right, up, forward);
    }

    /** Living-entity convenience overload: drops the forward offset. */
    public static RelativePortalPosition relativePositionLivingEntity(
            PortalInnerRect source, PortalOrientation sourceAxis, Position entityPosition,
            double entityWidth, double entityHeight) {
        return relativePosition(source, sourceAxis, entityPosition, entityWidth, entityHeight, true);
    }

    /**
     * Maps a relative offset into the destination portal and applies the perpendicular-portal
     * rotation, if any.
     *
     * @param entityWidth  the entity width (hitbox), needed again for the destination mapping
     * @param entityHeight the entity height (hitbox)
     */
    public static PortalExitPlacement exitPlacement(
            PortalInnerRect exit, PortalOrientation exitAxis, PortalOrientation sourceAxis,
            RelativePortalPosition relative, Velocity velocity, double yaw, double pitch,
            double entityWidth, double entityHeight) {
        if (exit == null || exitAxis == null || sourceAxis == null || relative == null || velocity == null) {
            throw new NullPointerException("exit, exitAxis, sourceAxis, relative and velocity must not be null");
        }
        if (!(entityWidth >= 0.0) || !(entityHeight >= 0.0)) {
            throw new IllegalArgumentException("entity dimensions must be >= 0");
        }

        boolean sameAxis = sourceAxis == exitAxis;
        int yawRotation = sameAxis ? 0 : 90;
        Velocity mappedVelocity = sameAxis ? velocity : new Velocity(velocity.z(), velocity.y(), -velocity.x());

        double halfWidth = entityWidth / 2.0 + (exit.width() - entityWidth) * relative.right();
        double upOffset = (exit.height() - entityHeight) * relative.up();
        double forwardOffset = 0.5 + relative.forward();

        boolean exitOnX = exitAxis == PortalOrientation.X_AXIS;
        BlockPos lowerLeft = exit.lowerLeft();
        double x = lowerLeft.x() + (exitOnX ? halfWidth : forwardOffset);
        double y = lowerLeft.y() + upOffset;
        double z = lowerLeft.z() + (exitOnX ? forwardOffset : halfWidth);

        Position position = new Position(x, y, z,
                PortalMathPrimitives.normalizeYaw(yaw + yawRotation), pitch);
        return new PortalExitPlacement(position, mappedVelocity, yawRotation);
    }

    /**
     * One-shot helper: computes the relative offset in the source rectangle and maps it into the
     * destination rectangle.
     */
    public static PortalExitPlacement computeExit(
            PortalInnerRect source, PortalOrientation sourceAxis, Position entityPosition,
            PortalInnerRect exit, PortalOrientation exitAxis,
            Velocity velocity, double yaw, double pitch,
            double entityWidth, double entityHeight, boolean zeroForward) {
        RelativePortalPosition relative =
                relativePosition(source, sourceAxis, entityPosition, entityWidth, entityHeight, zeroForward);
        return exitPlacement(exit, exitAxis, sourceAxis, relative, velocity, yaw, pitch, entityWidth, entityHeight);
    }

    private static double alongAxis(Position position, PortalOrientation axis) {
        return axis == PortalOrientation.X_AXIS ? position.x() : position.z();
    }

    private static double perpendicularAxis(Position position, PortalOrientation axis) {
        return axis == PortalOrientation.X_AXIS ? position.z() : position.x();
    }

    private static double perpendicularCoord(BlockPos pos, PortalOrientation axis) {
        return axis == PortalOrientation.X_AXIS ? pos.z() : pos.x();
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
