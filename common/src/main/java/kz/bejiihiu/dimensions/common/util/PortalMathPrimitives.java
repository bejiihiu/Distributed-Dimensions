package kz.bejiihiu.dimensions.common.util;

import kz.bejiihiu.dimensions.common.model.Velocity;

/**
 * Small geometric/rotational primitives shared by the portal code. Deliberately generic:
 * anything specific to nether-portal search/placement lives in the nether module instead.
 */
public final class PortalMathPrimitives {

    private PortalMathPrimitives() {
    }

    /** Normalizes a yaw angle into {@code [0, 360)}. */
    public static double normalizeYaw(double yaw) {
        if (!Double.isFinite(yaw)) {
            throw new IllegalArgumentException("yaw must be finite, got " + yaw);
        }
        double result = yaw % 360.0;
        return result < 0 ? result + 360.0 : result;
    }

    /**
     * Rotates a horizontal velocity vector by {@code turns} quarter-turns clockwise, as seen
     * from above (+Y looking down). {@code turns} is taken mod 4 and may be negative.
     *
     * <p>Mapping for one turn: {@code (x, z) -> (-z, x)} — i.e. southbound (+Z) turns into
     * westbound (-X). This matches Minecraft's yaw convention where yaw 0 faces +Z and yaw 90
     * faces -X.
     */
    public static Velocity rotateQuarterTurns(Velocity velocity, int turns) {
        int effective = ((turns % 4) + 4) % 4;
        double x = velocity.x(), y = velocity.y(), z = velocity.z();
        for (int i = 0; i < effective; i++) {
            double nx = -z, nz = x;
            x = nx;
            z = nz;
        }
        // canonicalize negative zeros so equality doesn't hinge on the sign bit
        if (x == 0.0) {
            x = 0.0;
        }
        if (z == 0.0) {
            z = 0.0;
        }
        return new Velocity(x, y, z);
    }

    /**
     * How many whole 90° turns the given yaw has turned clockwise relative to the reference,
     * rounded to the nearest turn and folded into {@code [0, 3]}. Non-quarter-turn yaws round
     * to the closest quarter — e.g. 44° rounds to 0 turns, 46° rounds to 1.
     */
    public static int quarterTurnsClockwise(double yaw, double referenceYaw) {
        double delta = normalizeYaw(yaw - referenceYaw);
        return (int) Math.floor((delta + 45.0) / 90.0) & 3;
    }

    /** Yaw after rotating {@code quarterTurns} quarter-turns clockwise. Result is in {@code [0, 360)}. */
    public static double yawWithQuarterTurns(double yaw, int quarterTurns) {
        int effective = ((quarterTurns % 4) + 4) % 4;
        return normalizeYaw(yaw + 90.0 * effective);
    }
}
