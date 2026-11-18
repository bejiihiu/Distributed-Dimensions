package kz.bejiihiu.dimensions.common.model;

/**
 * A world-space position with rotation. All fields are doubles; {@code yaw}/{@code pitch}
 * are Minecraft degrees. Every instance is finite — {@link NaN} and infinities are rejected
 * at construction time because positions flow over the wire and into the database.
 */
public record Position(double x, double y, double z, double yaw, double pitch) {

    public Position {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
    }

    public Position(double x, double y, double z) {
        this(x, y, z, 0.0, 0.0);
    }

    /**
     * Clamps the horizontal coordinates into a square border centred at {@code (centerX, centerZ)}
     * with the given half-size. Height (Y) is left untouched — border handling and height handling
     * are separate concerns (world border vs. build height).
     */
    public Position clampToSquareBorder(double centerX, double centerZ, double halfSize) {
        if (!(halfSize >= 0.0) || !Double.isFinite(halfSize)) {
            throw new IllegalArgumentException("halfSize must be finite and >= 0, got " + halfSize);
        }
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("border centre must be finite");
        }
        double cx = clamp(x, centerX - halfSize, centerX + halfSize);
        double cz = clamp(z, centerZ - halfSize, centerZ + halfSize);
        return new Position(cx, y, cz, yaw, pitch);
    }

    /** Clamps Y into {@code [minY, maxY]} (used for the void/world height limits). */
    public Position clampToHeight(double minY, double maxY) {
        if (!Double.isFinite(minY) || !Double.isFinite(maxY) || minY > maxY) {
            throw new IllegalArgumentException("invalid height range [" + minY + ", " + maxY + "]");
        }
        return new Position(x, clamp(y, minY, maxY), z, yaw, pitch);
    }

    /** 3D squared distance to another position. Kept squared on purpose — portal search compares cheaply. */
    public double distanceSquaredTo(Position other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Horizontal squared distance to another position (Y ignored). */
    public double horizontalDistanceSquaredTo(Position other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return dx * dx + dz * dz;
    }

    /** Same position with a different rotation. */
    public Position withRotation(double yaw, double pitch) {
        return new Position(x, y, z, yaw, pitch);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    private static void requireFinite(double v, String field) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("position field '" + field + "' must be finite, got " + v);
        }
    }
}
