package kz.bejiihiu.dimensions.paper.scheduler;

import kz.bejiihiu.dimensions.common.model.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Block → chunk routing math, including the negative-coordinate corners. */
class RegionMathTest {

    @Test
    void chunkCoordinatePositiveBoundaries() {
        assertEquals(0, RegionMath.chunkCoordinate(0));
        assertEquals(0, RegionMath.chunkCoordinate(15));
        assertEquals(1, RegionMath.chunkCoordinate(16));
        assertEquals(1, RegionMath.chunkCoordinate(31));
        assertEquals(2, RegionMath.chunkCoordinate(32));
    }

    @Test
    void chunkCoordinateNegativeBoundaries() {
        // floor division: block -1 lives in chunk -1, block -16 in chunk -1, block -17 in chunk -2
        assertEquals(-1, RegionMath.chunkCoordinate(-1));
        assertEquals(-1, RegionMath.chunkCoordinate(-16));
        assertEquals(-2, RegionMath.chunkCoordinate(-17));
        assertEquals(-2, RegionMath.chunkCoordinate(-32));
        assertEquals(-3, RegionMath.chunkCoordinate(-33));
    }

    @Test
    void chunkOriginBlockIsAligned() {
        assertEquals(0, RegionMath.chunkOriginBlock(0));
        assertEquals(16, RegionMath.chunkOriginBlock(1));
        assertEquals(-16, RegionMath.chunkOriginBlock(-1));
        assertEquals(-32, RegionMath.chunkOriginBlock(-2));
    }

    @Test
    void chunkCoordsOfMatchesAxisWise() {
        RegionMath.ChunkCoord coord = RegionMath.chunkCoordsOf(new BlockPos(31, 64, -17));
        assertEquals(1, coord.x());
        assertEquals(-2, coord.z());
        assertEquals(coord, new RegionMath.ChunkCoord(1, -2));
    }

    @Test
    void isInChunkChecksHorizontalBounds() {
        BlockPos inside = new BlockPos(15, 300, -1);
        assertTrue(RegionMath.isInChunk(inside, 0, -1));
        assertFalse(RegionMath.isInChunk(inside, 1, -1));
        assertFalse(RegionMath.isInChunk(inside, 0, 0));
    }
}
