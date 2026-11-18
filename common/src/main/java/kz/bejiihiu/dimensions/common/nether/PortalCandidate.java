package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.List;

/**
 * A grouped set of portal blocks forming one portal instance, as produced by
 * {@link PortalCandidateGrouper}.
 *
 * <p>{@link #lowerLeft()} and {@link #upperRight()} are the tight flood-fill bounds of the whole
 * group (the min/max corner over every member block). {@link #blocks()} is the sorted,
 * de-duplicated member list. {@link #orientation()} is inferred as follows:
 *
 * <ul>
 *   <li>every member shares one z (X-Y plane) → {@link PortalOrientation#X_AXIS};</li>
 *   <li>every member shares one x (Z-Y plane) → {@link PortalOrientation#Z_AXIS};</li>
 *   <li>otherwise (two portals merged through a shared block, a genuinely non-planar glob): the
 *       axis with the FEWER distinct coordinate values wins; ties resolve to X_AXIS. This is a
 *       deterministic rule, not a vanilla one — vanilla has no notion of grouping at all.</li>
 * </ul>
 *
 * <p>For the entity-exit rectangle prefer {@link PortalSearchResult#innerRectangle()}, which is
 * computed from the selected POI block with the vanilla largest-rectangle algorithm and can be
 * smaller than these bounds for damaged portals.
 */
public record PortalCandidate(PortalOrientation orientation, BlockPos lowerLeft, BlockPos upperRight, List<BlockPos> blocks) {

    public PortalCandidate {
        if (orientation == null || lowerLeft == null || upperRight == null || blocks == null) {
            throw new NullPointerException("candidate fields must not be null");
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a portal candidate needs at least one portal block");
        }
        blocks = blocks.stream().distinct().sorted((a, b) ->
                a.y() != b.y() ? Integer.compare(a.y(), b.y())
                        : a.x() != b.x() ? Integer.compare(a.x(), b.x())
                        : Integer.compare(a.z(), b.z())).toList();
    }

    /** Horizontal width of the flood-fill bounds along the portal's width axis. */
    public int width() {
        return axisSpan(lowerLeft, upperRight);
    }

    /** Height of the flood-fill bounds. */
    public int height() {
        return upperRight.y() - lowerLeft.y() + 1;
    }

    /** The plane's fixed horizontal coordinate (z for X_AXIS, x for Z_AXIS). */
    public int planeCoordinate() {
        return orientation == PortalOrientation.X_AXIS ? lowerLeft.z() : lowerLeft.x();
    }

    /** Membership test; sorted list, binary search. */
    public boolean contains(BlockPos pos) {
        int idx = binarySearch(blocks, pos);
        return idx >= 0;
    }

    private static int axisSpan(BlockPos min, BlockPos max) {
        int dx = max.x() - min.x();
        int dz = max.z() - min.z();
        // span along the wider horizontal axis (bounds are tight; for planar portals one of these is 0)
        return Math.max(dx, dz) + 1;
    }

    private static int binarySearch(List<BlockPos> sorted, BlockPos key) {
        int lo = 0, hi = sorted.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            BlockPos m = sorted.get(mid);
            int cmp = m.y() != key.y() ? Integer.compare(m.y(), key.y())
                    : m.x() != key.x() ? Integer.compare(m.x(), key.x())
                    : Integer.compare(m.z(), key.z());
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }
}
