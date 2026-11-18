package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.ArrayList;
import java.util.List;

/**
 * The inner (non-obsidian) rectangle of a portal, in blocks.
 *
 * <p>{@code lowerLeft} is the minimal corner: the lowest-y, minimal-width-axis block of the
 * rectangle's bottom row. {@code axis} says which horizontal axis the portal runs along (the width
 * axis): {@link PortalOrientation#X_AXIS} means the rectangle spans X and lies in the X-Y plane;
 * {@link PortalOrientation#Z_AXIS} spans Z. The remaining horizontal coordinate is the fixed plane
 * coordinate.
 */
public record PortalInnerRect(BlockPos lowerLeft, PortalOrientation axis, int width, int height) {

    public PortalInnerRect {
        if (lowerLeft == null || axis == null) {
            throw new NullPointerException("lowerLeft and axis must not be null");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("portal inner rectangle must be at least 1x1, got " + width + "x" + height);
        }
    }

    /** Whether the given block lies inside the rectangle. */
    public boolean contains(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        int along = axis == PortalOrientation.X_AXIS ? pos.x() - lowerLeft.x() : pos.z() - lowerLeft.z();
        int fixed = axis == PortalOrientation.X_AXIS ? pos.z() : pos.x();
        int planeFixed = axis == PortalOrientation.X_AXIS ? lowerLeft.z() : lowerLeft.x();
        return along >= 0 && along < width && pos.y() >= lowerLeft.y() && pos.y() < lowerLeft.y() + height && fixed == planeFixed;
    }

    /** The plane's fixed horizontal coordinate (z for X_AXIS, x for Z_AXIS). */
    public int planeCoordinate() {
        return axis == PortalOrientation.X_AXIS ? lowerLeft.z() : lowerLeft.x();
    }

    /** The maximal corner: {@code lowerLeft + (width - 1, height - 1, ...)} along the width axis. */
    public BlockPos upperRight() {
        return axis == PortalOrientation.X_AXIS
                ? new BlockPos(lowerLeft.x() + width - 1, lowerLeft.y() + height - 1, lowerLeft.z())
                : new BlockPos(lowerLeft.x(), lowerLeft.y() + height - 1, lowerLeft.z() + width - 1);
    }

    /** All blocks of the rectangle, in iteration order (along width, then y). */
    public List<BlockPos> blocks() {
        List<BlockPos> out = new ArrayList<>(width * height);
        for (int y = 0; y < height; y++) {
            for (int w = 0; w < width; w++) {
                out.add(axis == PortalOrientation.X_AXIS
                        ? new BlockPos(lowerLeft.x() + w, lowerLeft.y() + y, lowerLeft.z())
                        : new BlockPos(lowerLeft.x(), lowerLeft.y() + y, lowerLeft.z() + w));
            }
        }
        return out;
    }
}
