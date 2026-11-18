package kz.bejiihiu.dimensions.paper.chunktickets;

/**
 * Identity of a ticketed chunk. {@code worldKey} is the world's UID as a string for
 * real platforms (tests use arbitrary keys); the remaining fields are chunk coordinates.
 */
public record TicketKey(String worldKey, int chunkX, int chunkZ) {

    public TicketKey {
        if (worldKey == null || worldKey.isBlank()) {
            throw new IllegalArgumentException("worldKey must not be blank");
        }
    }

    /** The 8 chunk coordinates surrounding this chunk, in a fixed (x, z) offset order. */
    public static java.util.List<TicketKey> neighbors(TicketKey center) {
        java.util.List<TicketKey> ring = new java.util.ArrayList<>(8);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ring.add(new TicketKey(center.worldKey(), center.chunkX() + dx, center.chunkZ() + dz));
            }
        }
        return ring;
    }
}
