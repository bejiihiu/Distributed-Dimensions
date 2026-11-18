package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups portal blocks into portal instances by flood fill. A portal is a set of mutually
 * 6-connected {@code NETHER_PORTAL} blocks; every vertical column of portal blocks belongs to
 * exactly one portal, so a one-block-wide frame edge or a tall column is never counted as
 * multiple portals.
 *
 * <p>The grouping runs on the block list supplied by the caller; it does not consult the world.
 * Members of one group are all blocks reachable through 6-neighbor steps that stay inside the
 * input set. Because real nether portals are planar, a normal portal yields exactly one group;
 * two portals that share a block (frame corner merging) also yield one group — vanilla would
 * treat those as two POIs and pick the nearest to the target; this grouper merges them, and the
 * per-block nearest selection in {@link PortalSearch} recovers the same winner either way.
 */
public final class PortalCandidateGrouper {

    private PortalCandidateGrouper() {
    }

    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Partitions the input (after de-duplication) into connected components.
     *
     * @param portalBlocks input set; order and duplicates are irrelevant
     * @return the groups, sorted by their minimal block position for deterministic output
     */
    public static List<PortalCandidate> group(Collection<BlockPos> portalBlocks) {
        if (portalBlocks == null) {
            throw new NullPointerException("portalBlocks must not be null");
        }
        Set<BlockPos> pool = new HashSet<>(portalBlocks);
        List<PortalCandidate> result = new ArrayList<>();
        List<BlockPos> seedOrder = new ArrayList<>(pool);
        seedOrder.sort(Comparator.comparingInt(BlockPos::x).thenComparingInt(BlockPos::y).thenComparingInt(BlockPos::z));
        Set<BlockPos> visited = new HashSet<>(pool.size() * 2);
        for (BlockPos seed : seedOrder) {
            if (visited.contains(seed)) {
                continue;
            }
            List<BlockPos> members = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                members.add(current);
                for (int[] d : NEIGHBOURS) {
                    BlockPos next = new BlockPos(current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                    if (pool.contains(next) && visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            result.add(toCandidate(members));
        }
        result.sort(Comparator.comparingInt((PortalCandidate c) -> c.lowerLeft().x())
                .thenComparingInt(c -> c.lowerLeft().z())
                .thenComparingInt(c -> c.lowerLeft().y()));
        return result;
    }

    private static PortalCandidate toCandidate(List<BlockPos> members) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        Set<Integer> xs = new HashSet<>();
        Set<Integer> zs = new HashSet<>();
        for (BlockPos b : members) {
            minX = Math.min(minX, b.x());
            minY = Math.min(minY, b.y());
            minZ = Math.min(minZ, b.z());
            maxX = Math.max(maxX, b.x());
            maxY = Math.max(maxY, b.y());
            maxZ = Math.max(maxZ, b.z());
            xs.add(b.x());
            zs.add(b.z());
        }
        PortalOrientation orientation = inferOrientation(members, xs, zs);
        return new PortalCandidate(orientation, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), members);
    }

    private static PortalOrientation inferOrientation(List<BlockPos> members, Set<Integer> xs, Set<Integer> zs) {
        boolean allSameZ = zs.size() == 1;
        boolean allSameX = xs.size() == 1;
        if (allSameZ) {
            return PortalOrientation.X_AXIS;
        }
        if (allSameX) {
            return PortalOrientation.Z_AXIS;
        }
        // Non-planar cluster (merged portals / corrupted frame): thinner axis wins,
        // ties resolve to X_AXIS. Deterministic, documented as a non-vanilla extension.
        return zs.size() <= xs.size() ? PortalOrientation.X_AXIS : PortalOrientation.Z_AXIS;
    }
}
