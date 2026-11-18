package kz.bejiihiu.dimensions.common.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An active portal as it exists in the world, captured at a point in time.
 *
 * <p>{@code portalId} is a stable identity for the portal index — it is minted once when a
 * portal is first indexed. {@code blocks} are the actual {@code NETHER_PORTAL} block positions
 * (not the obsidian frame). {@code origin} is the lower corner of the frame (for indexing),
 * {@code exit} is the point arriving entities should be placed at.
 *
 * <p>The block list is sorted (x, then y, then z) and de-duplicated on construction so equality
 * and serialized form don't depend on the caller's iteration order. A portal's plane must be
 * axis-aligned and flat for the declared orientation.
 */
public record PortalDescriptor(
        UUID portalId,
        DimensionId dimension,
        PortalOrientation orientation,
        List<BlockPos> blocks,
        BlockPos origin,
        Position exit) {

    public PortalDescriptor {
        Objects.requireNonNull(portalId, "portalId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a portal descriptor needs at least one portal block");
        }
        blocks = blocks.stream().distinct().sorted(
                (a, b) -> a.x() != b.x() ? Integer.compare(a.x(), b.x())
                        : a.y() != b.y() ? Integer.compare(a.y(), b.y())
                        : Integer.compare(a.z(), b.z())).toList();
        validatePlane(blocks, orientation);
    }

    /** Tight bounds of the portal's inner (non-obsidian) rectangle, derived from the blocks. */
    public BlockPos innerMin() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (BlockPos b : blocks) {
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            minZ = Math.min(minZ, b.z());
        }
        return new BlockPos(minX, minY, minZ);
    }

    /** See {@link #innerMin()} — the opposite corner. */
    public BlockPos innerMax() {
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos b : blocks) {
            maxX = Math.max(maxX, b.x());
            maxY = Math.max(maxY, b.y());
            maxZ = Math.max(maxZ, b.z());
        }
        return new BlockPos(maxX, maxY, maxZ);
    }

    private static void validatePlane(List<BlockPos> blocks, PortalOrientation orientation) {
        // a flat, axis-aligned plane: every block shares the plane's fixed coordinate and Y span is rectangular
        int fixed = orientation == PortalOrientation.X_AXIS ? blocks.getFirst().z() : blocks.getFirst().x();
        for (BlockPos b : blocks) {
            int axis = orientation == PortalOrientation.X_AXIS ? b.z() : b.x();
            if (axis != fixed) {
                throw new IllegalArgumentException(
                        "portal blocks do not lie in one " + orientation + " plane (block " + b + ")");
            }
        }
    }
}
