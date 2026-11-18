package kz.bejiihiu.dimensions.common.model;

/**
 * Integer block coordinates inside a world. Block positions are exact; use {@link Position}
 * for sub-block entity positions.
 */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Horizontal block distance squared (no Y). Cheap comparisons stay squared. */
    public long horizontalDistanceSquared(BlockPos other) {
        long dx = (long) this.x - other.x;
        long dz = (long) this.z - other.z;
        return dx * dx + dz * dz;
    }

    /** Full 3D distance squared. */
    public long distanceSquared(BlockPos other) {
        long dx = (long) this.x - other.x;
        long dy = (long) this.y - other.y;
        long dz = (long) this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
