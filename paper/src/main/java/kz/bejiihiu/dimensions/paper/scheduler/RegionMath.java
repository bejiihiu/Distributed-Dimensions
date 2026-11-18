package kz.bejiihiu.dimensions.paper.scheduler;

import kz.bejiihiu.dimensions.common.model.BlockPos;

/**
 * Block ↔ chunk coordinate math used when routing work onto the region scheduler.
 *
 * <p>Folia assigns region boundaries at runtime and does not expose them through the
 * public API, so there is deliberately no "region id" concept here: the facade routes
 * by chunk coordinates, which the platform maps onto the owning region. The math below
 * is the only routing decision made above the platform layer, and it uses floor
 * division so negative coordinates land in the correct (negative) chunk.
 */
public final class RegionMath {

    public static final int CHUNK_LENGTH = 16;

    private RegionMath() {
    }

    /** Chunk coordinate containing a block coordinate. Handles negative values. */
    public static int chunkCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, CHUNK_LENGTH);
    }

    /** Minimum (aligned) block coordinate of the chunk. */
    public static int chunkOriginBlock(int chunkCoordinate) {
        return chunkCoordinate * CHUNK_LENGTH;
    }

    /** Whether the block position lies inside the given chunk. */
    public static boolean isInChunk(BlockPos pos, int chunkX, int chunkZ) {
        return chunkCoordinate(pos.x()) == chunkX && chunkCoordinate(pos.z()) == chunkZ;
    }

    /** Chunk coordinates of a block position. */
    public static ChunkCoord chunkCoordsOf(BlockPos pos) {
        return new ChunkCoord(chunkCoordinate(pos.x()), chunkCoordinate(pos.z()));
    }

    /** A chunk coordinate pair. Value type, no identity semantics. */
    public record ChunkCoord(int x, int z) {
    }
}
