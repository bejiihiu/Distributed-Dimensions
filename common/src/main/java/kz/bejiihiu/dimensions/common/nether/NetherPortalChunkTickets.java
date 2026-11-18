package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;

import java.util.List;

/**
 * Pure chunk-offset math for the "arrival chunk ticket" that vanilla grants when anything
 * teleports through a nether portal: the destination chunk is held at load level 30
 * (entity-ticking) for 15 seconds, and eight neighbouring chunks are held at decaying levels.
 *
 * <p>This class only supplies the constants and offsets — granting and removing tickets is the
 * platform side's job. The vanilla timer of 300 ticks is <b>not</b> refreshed by the platform;
 * callers re-issue the ticket on every transfer, which resets the hold, and explicitly remove it
 * after {@link #TTL_TICKS} without a refresh.
 *
 * <p>One plugin ticket per chunk is the Paper API constraint; the ring exists so the destination
 * chunk loader keeps working on both sides of a transfer for construction contraptions.
 */
public final class NetherPortalChunkTickets {

    private NetherPortalChunkTickets() {
    }

    /** Vanilla hold time for an arrival chunk: 15 seconds at 20 ticks per second. */
    public static final int TTL_TICKS = 300;

    /** Load level that makes a chunk entity-ticking. */
    public static final int ENTITY_TICKING_LOAD_LEVEL = 30;

    /** Chunk coordinate component of one offset. */
    public record ChunkOffset(int chunkX, int chunkZ) {
    }

    /** The center chunk (destination itself). */
    public static final ChunkOffset CENTER = new ChunkOffset(0, 0);

    /** The eight neighbouring chunks, in reading order (north to south, west to east). */
    public static final List<ChunkOffset> RING = List.of(
            new ChunkOffset(-1, -1), new ChunkOffset(0, -1), new ChunkOffset(1, -1),
            new ChunkOffset(-1, 0),                         new ChunkOffset(1, 0),
            new ChunkOffset(-1, 1),  new ChunkOffset(0, 1),  new ChunkOffset(1, 1));

    /** Center plus ring — the vanilla entity-ticking destination chunk and its decaying neighbours. */
    public static final List<ChunkOffset> CENTER_AND_RING = List.of(
            CENTER,
            RING.get(0), RING.get(1), RING.get(2), RING.get(3), RING.get(4), RING.get(5), RING.get(6), RING.get(7));

    /**
     * Which chunks a transfer should ticket. {@code includeRing=false} tickets only the arrival
     * chunk; {@code includeRing=true} adds the eight neighbours at lower levels.
     */
    public static List<ChunkOffset> arrivalChunks(boolean includeRing) {
        return includeRing ? CENTER_AND_RING : List.of(CENTER);
    }

    /** Chunk offset of the chunk containing the given block position. */
    public static ChunkOffset chunkOf(BlockPos pos) {
        return new ChunkOffset(pos.x() >> 4, pos.z() >> 4);
    }

    /** Chunk offset of the chunk containing the given position (floor semantics on negatives). */
    public static ChunkOffset chunkOf(Position pos) {
        return new ChunkOffset((int) Math.floor(pos.x() / 16.0), (int) Math.floor(pos.z() / 16.0));
    }

    /** Absolute chunk coordinates: {@code chunkOf + ringOffset}. */
    public static ChunkOffset resolve(ChunkOffset base, ChunkOffset offset) {
        return new ChunkOffset(base.chunkX() + offset.chunkX(), base.chunkZ() + offset.chunkZ());
    }
}
