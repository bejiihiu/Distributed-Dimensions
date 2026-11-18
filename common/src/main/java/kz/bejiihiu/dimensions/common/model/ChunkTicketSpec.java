package kz.bejiihiu.dimensions.common.model;

/**
 * Request to hold a chunk loaded on the destination side of a portal transfer — the distributed
 * equivalent of the vanilla chunk ticket ("chunk loader") that vanilla grants when anything
 * teleports through an entity portal.
 *
 * <p>{@code ttlTicks} is measured from the moment the target backend grants the ticket.
 * Vanilla holds arrival chunks for 15 seconds (300 ticks) and refreshes the timer on every
 * pass; callers should refresh by re-issuing the ticket rather than extending it.
 *
 * <p>{@code ring = true} asks for the eight neighbouring chunks as well, each at a lower
 * ticket level, matching the vanilla behaviour of a fully-entity-ticking destination chunk plus
 * a decaying ring.
 */
public record ChunkTicketSpec(DimensionId dimension, int chunkX, int chunkZ, boolean ring, int ttlTicks) {

    /** Vanilla hold time for an arrival chunk: 15 seconds. */
    public static final int DEFAULT_TTL_TICKS = 300;

    /** Paper load level that makes a chunk entity-ticking (level 30). */
    public static final int ENTITY_TICKING_LOAD_LEVEL = 30;

    public ChunkTicketSpec {
        java.util.Objects.requireNonNull(dimension, "dimension");
        if (ttlTicks < 1 || ttlTicks > 3600) {
            throw new IllegalArgumentException("ttlTicks must be in [1, 3600], got " + ttlTicks);
        }
    }

    /** The standard arrival ticket for a portal destination chunk. */
    public static ChunkTicketSpec destinationPortal(DimensionId dimension, int chunkX, int chunkZ, boolean ring) {
        return new ChunkTicketSpec(dimension, chunkX, chunkZ, ring, DEFAULT_TTL_TICKS);
    }
}
