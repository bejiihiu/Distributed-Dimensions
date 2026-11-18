package kz.bejiihiu.dimensions.common.model;

/**
 * A finite velocity vector. Minecraft velocities are blocks per tick; no clamping is applied
 * here — the caller decides what's sane for their use case.
 */
public record Velocity(double x, double y, double z) {

    public Velocity {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public static final Velocity ZERO = new Velocity(0.0, 0.0, 0.0);

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    private static void requireFinite(double v, String field) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("velocity field '" + field + "' must be finite, got " + v);
        }
    }
}
