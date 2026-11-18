package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherPortalChunkTicketsTest {

    @Test
    void arrivalChunksIncludeCenterWithoutRing() {
        List<NetherPortalChunkTickets.ChunkOffset> chunks = NetherPortalChunkTickets.arrivalChunks(false);
        assertEquals(List.of(NetherPortalChunkTickets.CENTER), chunks);
    }

    @Test
    void arrivalChunksWithRingHasNineOffsets() {
        List<NetherPortalChunkTickets.ChunkOffset> chunks = NetherPortalChunkTickets.arrivalChunks(true);
        assertEquals(9, chunks.size());
        assertTrue(chunks.contains(new NetherPortalChunkTickets.ChunkOffset(0, 0)));
        assertTrue(chunks.contains(new NetherPortalChunkTickets.ChunkOffset(-1, -1)));
        assertTrue(chunks.contains(new NetherPortalChunkTickets.ChunkOffset(1, 1)));
        // the ring is exactly the eight neighbours plus the center
        assertEquals(1, chunks.stream().filter(o -> o.chunkX() == 0 && o.chunkZ() == 0).count());
    }

    @Test
    void ringIsDistinctAndAroundCenter() {
        assertEquals(8, NetherPortalChunkTickets.RING.stream().distinct().count());
        for (NetherPortalChunkTickets.ChunkOffset offset : NetherPortalChunkTickets.RING) {
            assertTrue(Math.abs(offset.chunkX()) <= 1 && Math.abs(offset.chunkZ()) <= 1);
            assertTrue(offset.chunkX() != 0 || offset.chunkZ() != 0);
        }
    }

    @Test
    void chunkOfBlockPositionUsesArithmeticShift() {
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(0, 0), NetherPortalChunkTickets.chunkOf(new BlockPos(0, 64, 0)));
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(0, 0), NetherPortalChunkTickets.chunkOf(new BlockPos(15, 64, 15)));
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(1, 1), NetherPortalChunkTickets.chunkOf(new BlockPos(16, 64, 16)));
        // negative coordinates: -1 is in chunk -1 (floor semantics)
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(-1, -1), NetherPortalChunkTickets.chunkOf(new BlockPos(-1, 64, -1)));
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(-1, -1), NetherPortalChunkTickets.chunkOf(new BlockPos(-16, 64, -16)));
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(-2, -2), NetherPortalChunkTickets.chunkOf(new BlockPos(-17, 64, -17)));
    }

    @Test
    void chunkOfPositionFloorsFractionals() {
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(0, 0), NetherPortalChunkTickets.chunkOf(new Position(15.999, 64, 15.999)));
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(1, 1), NetherPortalChunkTickets.chunkOf(new Position(16.0, 64, 16.0)));
        // 0.5/16 = 0.03 -> floor 0 for positive, but -0.5/16 -> floor -1 for negative
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(-1, -1), NetherPortalChunkTickets.chunkOf(new Position(-0.5, 64, -0.5)));
    }

    @Test
    void resolveOffsetsFromBase() {
        NetherPortalChunkTickets.ChunkOffset base = new NetherPortalChunkTickets.ChunkOffset(10, -7);
        assertEquals(new NetherPortalChunkTickets.ChunkOffset(9, -8), NetherPortalChunkTickets.resolve(base, NetherPortalChunkTickets.RING.get(0)));
        assertEquals(base, NetherPortalChunkTickets.resolve(base, NetherPortalChunkTickets.CENTER));
    }

    @Test
    void ttlMatchesVanillaFifteenSeconds() {
        assertEquals(300, NetherPortalChunkTickets.TTL_TICKS);
        assertEquals(30, NetherPortalChunkTickets.ENTITY_TICKING_LOAD_LEVEL);
    }
}
