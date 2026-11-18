package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;

/**
 * Where an entity arrives: its exact position, velocity and heading after the portal transfer.
 *
 * @param position          entity position inside the destination portal (bottom-middle of the hitbox
 *                          semantics are the caller's responsibility; see PortalExitCalculator)
 * @param velocity          velocity after the transfer (rotated when the portals are perpendicular)
 * @param yawRotationDegrees 0 when both portals share an axis, 90 when they are perpendicular
 *                          (yaw was increased by exactly this much)
 */
public record PortalExitPlacement(Position position, Velocity velocity, double yawRotationDegrees) {

    public PortalExitPlacement {
        if (position == null || velocity == null) {
            throw new NullPointerException("position and velocity must not be null");
        }
        if (!Double.isFinite(yawRotationDegrees)) {
            throw new IllegalArgumentException("yawRotationDegrees must be finite, got " + yawRotationDegrees);
        }
    }
}
