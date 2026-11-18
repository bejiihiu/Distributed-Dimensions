package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.nether.NetherPortalConstants;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.paper.scheduler.RegionMath;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure search/creation geometry for the destination-side portal work. Everything here is
 * allocation-light, side-effect free and unit-testable — no Bukkit world touch, no
 * persistence. Y is never part of these boxes: the vanilla search is a horizontally
 * unrestricted-height square (|dx| &le; radius &amp;&amp; |dz| &le; radius).
 */
public final class NetherSearchAreas {

    private NetherSearchAreas() {
    }

    /** The floored block position of a world-space position (vanilla {@code BlockPos.ofFloored}). */
    public static BlockPos floorToBlock(Position position) {
        return new BlockPos((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()));
    }

    /** Radius of the destination-side search; 16 into the nether, 128 into the overworld. */
    public static int searchRadius(boolean destinationIsNether) {
        return NetherPortalConstants.searchRadiusForDestination(destinationIsNether);
    }

    /**
     * Inclusive block bounds of the horizontal search square around {@code center}.
     * The border is NOT applied here — the search applies it per candidate (as vanilla does
     * with {@code worldBorder::contains}), and the caller may clamp it for chunk budgeting.
     */
    public static int[] searchBox(BlockPos center, int radius) {
        return new int[]{center.x() - radius, center.x() + radius, center.z() - radius, center.z() + radius};
    }

    /**
     * Chunk coordinates covering the inclusive search box, in spiral order from the center
     * (vanilla's {@code BlockPos.iterateInSquare} order — center first, then square rings
     * E→S→W→N), de-duplicated, capped at {@code maxChunks}. The cap is the on-demand index
     * fill budget; pass {@code Integer.MAX_VALUE} for the full (degraded-mode) scan. The
     * spiral only affects which chunks are read first when the budget is tight — selection
     * is by distance, never by scan order.
     */
    public static List<RegionMath.ChunkCoord> spiralChunks(BlockPos center, int radius, int maxChunks) {
        List<RegionMath.ChunkCoord> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int[] offset : spiralOffsets(radius)) {
            RegionMath.ChunkCoord coord = new RegionMath.ChunkCoord(
                    RegionMath.chunkCoordinate(center.x() + offset[0]),
                    RegionMath.chunkCoordinate(center.z() + offset[1]));
            long key = chunkKey(coord.x(), coord.z());
            if (seen.add(key)) {
                out.add(coord);
                if (out.size() >= maxChunks) {
                    return out;
                }
            }
        }
        return out;
    }

    /** All column offsets in spiral order: ring 0 is the center, then rings of increasing radius. */
    private static List<int[]> spiralOffsets(int radius) {
        List<int[]> out = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        out.add(new int[]{0, 0});
        for (int ring = 1; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                out.add(new int[]{ring, dz});          // east column, north → south
            }
            for (int dx = ring - 1; dx >= -ring; dx--) {
                out.add(new int[]{dx, ring});          // south row, east → west
            }
            for (int dz = ring - 1; dz >= -ring; dz--) {
                out.add(new int[]{-ring, dz});         // west column, south → north
            }
            for (int dx = -ring + 1; dx <= ring; dx++) {
                out.add(new int[]{dx, -ring});         // north row, west → east
            }
        }
        return out;
    }

    /** All chunks overlapped by an inclusive block AABB (usually a handful of chunks). */
    public static List<RegionMath.ChunkCoord> chunksOverlapping(int minX, int minZ, int maxX, int maxZ) {
        List<RegionMath.ChunkCoord> out = new ArrayList<>();
        int minChunkX = RegionMath.chunkCoordinate(minX);
        int maxChunkX = RegionMath.chunkCoordinate(maxX);
        int minChunkZ = RegionMath.chunkCoordinate(minZ);
        int maxChunkZ = RegionMath.chunkCoordinate(maxZ);
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                out.add(new RegionMath.ChunkCoord(cx, cz));
            }
        }
        return out;
    }

    /** Packs chunk coordinates for set bookkeeping (handles negatives). */
    public static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xffffffffL);
    }

    /** Unpacks the X half of a packed key. */
    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    /** Unpacks the Z half of a packed key. */
    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    /**
     * Bounds from a border centre and size — the runtime border read on the destination
     * backend ({@code world.getWorldBorder()}). The border clamp is a horizontal clamp of
     * the vanilla "ofFloored inside border" chain; bounds are centred on the border centre,
     * not on the origin.
     */
    public static WorldBorderBounds boundsFromCenterAndSize(double centerX, double centerZ, double size) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ) || !Double.isFinite(size) || size < 0) {
            throw new IllegalArgumentException("center/size must be finite, size >= 0");
        }
        double half = size / 2.0;
        return new WorldBorderBounds(centerX - half, centerZ - half, centerX + half, centerZ + half);
    }

    /** Y of the first position above the highest (blocking or fluid) block — vanilla MOTION_BLOCKING + 1. */
    public static int motionBlockingTopY(int highestBlockY, int bottomY) {
        if (highestBlockY < bottomY) {
            return bottomY;
        }
        return highestBlockY + 1;
    }
}
