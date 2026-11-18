package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Portal search, Java Edition semantics: find the nearest active portal to the destination
 * coordinates within a horizontal square radius, over the full world height.
 *
 * <p>Algorithm (matching the decompiled 1.21.x sources):
 * <ol>
 *   <li>Every currently-active portal block is a point of interest. The horizontal search is a
 *       SQUARE (|dx| &le; radius &amp;&amp; |dz| &le; radius), not a circle; Y is unrestricted
 *       (full world height). The search origin is a floored block position.</li>
 *   <li>Blocks outside the destination world border are discarded (vanilla
 *       {@code filter(worldBorder::contains)}).</li>
 *   <li>The closest POI wins by 3D SQUARED distance (never Manhattan, never square-rooted); the
 *       Y difference counts fully. Ties in squared distance are broken by lower Y (vanilla), and
 *       any remaining tie by lower x then lower z — vanilla leaves those to POI iteration order,
 *       which is arbitrary; this extension keeps the pick deterministic.</li>
 *   <li>The winning POI is anchored into a portal instance via flood fill ({@link PortalCandidateGrouper})
 *       for reporting, and the destination inner rectangle is computed with the vanilla largest-
 *       rectangle algorithm ({@link LargestRectangleFinder}) for entity placement.</li>
 * </ol>
 *
 * <p>Radius: 16 blocks when the destination is the nether, 128 when it is the overworld (see
 * {@link NetherPortalConstants#searchRadiusForDestination(boolean)}). Vanilla applies the radius
 * since 1.16.2; before that both directions searched 128.
 */
public final class PortalSearch {

    private PortalSearch() {
    }

    /**
     * Selects the winning portal, if any.
     *
     * @param target floored search origin; use {@link #select(Position, PortalCandidateView, int, WorldBorderBounds)}
     *               to have the flooring done for you
     */
    public static Optional<PortalSearchResult> select(BlockPos target, PortalCandidateView view, int radiusInBlocks, WorldBorderBounds border) {
        if (target == null || view == null || border == null) {
            throw new NullPointerException("target, view and border must not be null");
        }
        if (radiusInBlocks < 0) {
            throw new IllegalArgumentException("radius must be >= 0, got " + radiusInBlocks);
        }
        Collection<BlockPos> raw = view.portalBlocks();
        if (raw == null) {
            throw new NullPointerException("PortalCandidateView.portalBlocks() returned null");
        }

        // vanilla: square horizontal filter, full height, border filter — on the floored target
        Set<BlockPos> eligible = new HashSet<>(raw.size() * 2);
        for (BlockPos block : raw) {
            if (block == null) {
                continue;
            }
            if (Math.abs(block.x() - target.x()) > radiusInBlocks || Math.abs(block.z() - target.z()) > radiusInBlocks) {
                continue;
            }
            if (!border.containsBlock(block.x(), block.z())) {
                continue;
            }
            eligible.add(block);
        }
        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        // vanilla: min over POIs by 3D squared distance, tie-break lower Y — extended with x/z for determinism
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (BlockPos block : eligible) {
            double distance = squaredDistance(target, block);
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && compareTieBreak(block, best) < 0)) {
                best = block;
                bestDistance = distance;
            }
        }

        List<PortalCandidate> candidates = PortalCandidateGrouper.group(eligible);
        PortalCandidate winner = null;
        for (PortalCandidate candidate : candidates) {
            if (candidate.contains(best)) {
                winner = candidate;
                break;
            }
        }
        if (winner == null) {
            // unreachable: 'best' comes from the same eligible set that was grouped
            throw new IllegalStateException("grouped candidates do not cover the selected block");
        }

        Set<BlockPos> memberSet = new HashSet<>(winner.blocks());
        PortalInnerRect innerRectangle =
                LargestRectangleFinder.largestRectangle(best, winner.orientation(), memberSet::contains);
        return Optional.of(new PortalSearchResult(winner, best, bestDistance, innerRectangle));
    }

    /**
     * Selects the winning portal, flooring the search target to a block position first (vanilla
     * {@code BlockPos.ofFloored} on the scaled destination coordinate).
     */
    public static Optional<PortalSearchResult> select(Position target, PortalCandidateView view, int radiusInBlocks, WorldBorderBounds border) {
        if (target == null) {
            throw new NullPointerException("target must not be null");
        }
        return select(new BlockPos((int) Math.floor(target.x()), (int) Math.floor(target.y()), (int) Math.floor(target.z())),
                view, radiusInBlocks, border);
    }

    /**
     * Selects with the destination-dimension radius: 16 for the nether, 128 for the overworld.
     */
    public static Optional<PortalSearchResult> selectForDestination(Position target, PortalCandidateView view, boolean destinationIsNether, WorldBorderBounds border) {
        return select(target, view, NetherPortalConstants.searchRadiusForDestination(destinationIsNether), border);
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = (double) a.x() - b.x();
        double dy = (double) a.y() - b.y();
        double dz = (double) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int compareTieBreak(BlockPos a, BlockPos b) {
        if (a.y() != b.y()) {
            return Integer.compare(a.y(), b.y());
        }
        if (a.x() != b.x()) {
            return Integer.compare(a.x(), b.x());
        }
        return Integer.compare(a.z(), b.z());
    }
}
