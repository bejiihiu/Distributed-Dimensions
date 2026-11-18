package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.paper.scheduler.RegionMath;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetherSearchAreasTest {

    @Test
    void floorToBlockFloorsNegativeCoordinates() {
        assertEquals(new BlockPos(-30, 64, -16),
                NetherSearchAreas.floorToBlock(new Position(-29.5, 64.25, -15.25, 0.0, 0.0)));
        assertEquals(new BlockPos(-1, 64, -1),
                NetherSearchAreas.floorToBlock(new Position(-0.5, 64.9, -0.5, 0.0, 0.0)));
    }

    @Test
    void searchRadiusMatchesVanillaForBothDirections() {
        assertEquals(16, NetherSearchAreas.searchRadius(true));
        assertEquals(128, NetherSearchAreas.searchRadius(false));
    }

    @Test
    void searchBoxIsSymmetricSquare() {
        assertTrue(java.util.Arrays.equals(
                new int[]{-100, 100, -600, -400},
                NetherSearchAreas.searchBox(new BlockPos(0, 64, -500), 100)));
    }

    @Test
    void chunksOverlappingHandlesNegativesAndPositiveCoordinates() {
        List<RegionMath.ChunkCoord> chunks = NetherSearchAreas.chunksOverlapping(-17, -1, 31, 15);
        // x: floorDiv(-17,16)=-2 .. floorDiv(31,16)=1 → 4 chunks; z: floorDiv(-1,16)=-1 .. 0 → 2 → 8 total
        assertEquals(8, chunks.size());
        assertTrue(chunks.contains(new RegionMath.ChunkCoord(-2, -1)));
        assertTrue(chunks.contains(new RegionMath.ChunkCoord(1, 0)));

        List<RegionMath.ChunkCoord> single = NetherSearchAreas.chunksOverlapping(0, 0, 15, 15);
        assertEquals(List.of(new RegionMath.ChunkCoord(0, 0)), single);
    }

    @Test
    void spiralChunksAreDedupedAndCapped() {
        List<RegionMath.ChunkCoord> full = NetherSearchAreas.spiralChunks(new BlockPos(0, 64, 0), 16, Integer.MAX_VALUE);
        // 33x33 blocks fit in exactly 3x3 chunks
        assertEquals(9, full.size());
        assertTrue(full.contains(new RegionMath.ChunkCoord(0, 0)));
        assertTrue(full.contains(new RegionMath.ChunkCoord(-1, -1)));
        assertTrue(full.contains(new RegionMath.ChunkCoord(1, 1)));

        List<RegionMath.ChunkCoord> capped = NetherSearchAreas.spiralChunks(new BlockPos(0, 64, 0), 16, 4);
        assertEquals(4, capped.size());

        List<RegionMath.ChunkCoord> overworldBox = NetherSearchAreas.spiralChunks(new BlockPos(0, 64, 0), 128, 64);
        assertEquals(64, overworldBox.size());
    }

    @Test
    void chunkKeyRoundTrips() {
        long key = NetherSearchAreas.chunkKey(-7, 12345);
        assertEquals(-7, NetherSearchAreas.chunkKeyX(key));
        assertEquals(12345, NetherSearchAreas.chunkKeyZ(key));
    }

    @Test
    void borderBoundsFromCenterAndSize() {
        WorldBorderBounds bounds = NetherSearchAreas.boundsFromCenterAndSize(100.0, -200.0, 1000.0);
        assertEquals(-400.0, bounds.west());
        assertEquals(600.0, bounds.east());
        assertEquals(-700.0, bounds.north());
        assertEquals(300.0, bounds.south());
        assertThrows(IllegalArgumentException.class,
                () -> NetherSearchAreas.boundsFromCenterAndSize(0.0, 0.0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> NetherSearchAreas.boundsFromCenterAndSize(Double.NaN, 0.0, 1.0));
    }

    @Test
    void motionBlockingTopYIsFirstFreePosition() {
        assertEquals(71, NetherSearchAreas.motionBlockingTopY(70, 0));
        assertEquals(0, NetherSearchAreas.motionBlockingTopY(-1, 0));
    }
}
