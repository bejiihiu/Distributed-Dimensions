package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.DimensionId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The portal index — an accelerating index for "which portals might be near these
 * coordinates?". It never answers "is this portal real?": callers must confirm against the
 * live world. A stale index entry costs one wasted world check; a wrong answer about the
 * world would be fatal, which is why this type can only narrow candidates.
 */
public interface PortalIndex {

    /** Inserts or replaces the entry (matched by portal id). */
    void upsert(PortalIndexEntry entry);

    Optional<PortalIndexEntry> findById(UUID portalId);

    /**
     * Candidate entries whose inner bounds overlap the inclusive horizontal box
     * {@code [minX..maxX] × [minZ..maxZ]} — full height, like the vanilla portal search.
     * The result is ordered by {@code origin_x, origin_z} for determinism.
     */
    List<PortalIndexEntry> candidatesIn(DimensionId dimension, int minX, int maxX, int minZ, int maxZ);

    /** Removes the entry; returns true when one was removed. */
    boolean delete(UUID portalId);

    /** How many entries the index holds for the dimension (diagnostics). */
    long count(DimensionId dimension);
}
