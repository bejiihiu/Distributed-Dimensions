package kz.bejiihiu.dimensions.common.util;

import kz.bejiihiu.dimensions.common.model.Position;

/**
 * Squared-distance helpers. Distances are never square-rooted on hot paths — comparisons
 * (nearest portal, sort order) only need monotonic transforms.
 */
public final class SquaredDistance {

    private SquaredDistance() {
    }

    /** 3D squared distance between two positions. */
    public static double between3D(Position a, Position b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    public static double between3D(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Horizontal squared distance (Y ignored). Used for radius pre-filters. */
    public static double betweenHorizontal(Position a, Position b) {
        double dx = a.x() - b.x();
        double dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    public static double betweenHorizontal(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2, dz = z1 - z2;
        return dx * dx + dz * dz;
    }
}
