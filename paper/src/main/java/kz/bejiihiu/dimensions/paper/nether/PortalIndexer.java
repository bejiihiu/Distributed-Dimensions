package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.nether.PortalCandidate;
import kz.bejiihiu.dimensions.common.persistence.PortalIndex;
import kz.bejiihiu.dimensions.common.persistence.PortalIndexEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The portal index as the destination-side search accelerator. The index is a hint only —
 * the world is the truth. Every read is paired with a live harvest; every harvest reconciles
 * (stale entries are deleted, freshly discovered portals are inserted, bounded), so the index
 * self-heals continuously instead of aging on a timer. Degraded backends (DB unreachable)
 * surface as {@link Optional#empty()} and the caller falls back to a full world scan — never
 * to "pretend no portals exist".
 */
final class PortalIndexer {

    /** Cap on inserts per arrival — the index is bounded even in portal-dense worlds. */
    static final int INSERT_LIMIT = 32;

    private final NetherPortalStore store;
    private final Consumer<String> diagnostics;

    PortalIndexer(NetherPortalStore store, Consumer<String> diagnostics) {
        this.store = store;
        this.diagnostics = diagnostics == null ? line -> { } : diagnostics;
    }

    /**
     * Candidate entries overlapping the search AABB.
     *
     * @return the candidates, or {@code Optional.empty()} when the index is unavailable
     *         (degraded backend or a database error) — the caller must fall back to a
     *         full scan in that case
     */
    Optional<List<PortalIndexEntry>> candidates(DimensionId dimension, int minX, int maxX, int minZ, int maxZ) {
        Optional<PortalIndex> index = index();
        if (index.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(index.get().candidatesIn(dimension, minX, maxX, minZ, maxZ));
        } catch (RuntimeException e) {
            diagnostics.accept("portal index query failed (" + e.getMessage() + "); falling back to a full world scan for this arrival");
            return Optional.empty();
        }
    }

    /** True when the index is unavailable (degraded backend); the caller skips all DB writes too. */
    boolean degraded() {
        return index().isEmpty();
    }

    /**
     * Reconciles the index against a harvest: stale entries (their AABB overlaps the scanned
     * area but no portal exists there anymore) are deleted; harvested portals not yet indexed
     * are inserted, bounded by {@link #INSERT_LIMIT}. Never throws — indexing is hygiene.
     */
    void reconcile(DimensionId dimension, List<PortalIndexEntry> queried, List<BlockPos> harvestedBlocks,
                   List<PortalCandidate> harvestedCandidates) {
        Optional<PortalIndex> index = index();
        if (index.isEmpty()) {
            return;
        }
        PortalIndex idx = index.get();
        // delete entries whose overlap window was scanned and produced nothing
        for (PortalIndexEntry entry : queried) {
            if (!containsAnyPortalBlock(entry, harvestedBlocks)) {
                try {
                    if (idx.delete(entry.portalId())) {
                        diagnostics.accept("portal index: dropped stale entry " + entry.portalId()
                                + " at " + entry.innerMin() + ".." + entry.innerMax() + " (dimension " + dimension.key() + ")");
                    }
                } catch (RuntimeException e) {
                    diagnostics.accept("portal index: stale delete failed for " + entry.portalId() + ": " + e.getMessage());
                }
            }
        }
        // insert new discoveries, bounded
        int inserted = 0;
        Instant now = Instant.now();
        for (PortalCandidate candidate : harvestedCandidates) {
            if (inserted >= INSERT_LIMIT) {
                break;
            }
            if (overlapsAny(candidate, queried)) {
                continue;
            }
            try {
                idx.upsert(entryOf(dimension, candidate, now));
                inserted++;
            } catch (RuntimeException e) {
                diagnostics.accept("portal index: insert failed for candidate at " + candidate.lowerLeft()
                        + ": " + e.getMessage());
                return;
            }
        }
    }

    /** Indexes a portals discovered by the creation path (best effort). */
    void insertDiscovered(DimensionId dimension, PortalCandidate candidate) {
        Optional<PortalIndex> index = index();
        if (index.isEmpty()) {
            return;
        }
        try {
            index.get().upsert(entryOf(dimension, candidate, Instant.now()));
        } catch (RuntimeException e) {
            diagnostics.accept("portal index: post-creation insert failed for candidate at "
                    + candidate.lowerLeft() + ": " + e.getMessage());
        }
    }

    private Optional<PortalIndex> index() {
        try {
            return store.index();
        } catch (RuntimeException e) {
            diagnostics.accept("portal index unavailable: " + e.getMessage());
            return Optional.empty();
        }
    }

    static PortalIndexEntry entryOf(DimensionId dimension, PortalCandidate candidate, Instant at) {
        PortalOrientation orientation = candidate.orientation();
        BlockPos lowerLeft = candidate.lowerLeft();
        BlockPos upperRight = candidate.upperRight();
        return new PortalIndexEntry(UUID.randomUUID(), dimension, orientation,
                lowerLeft, lowerLeft, upperRight, at, at);
    }

    static boolean containsAnyPortalBlock(PortalIndexEntry entry, List<BlockPos> blocks) {
        for (BlockPos block : blocks) {
            if (block.x() >= entry.innerMin().x() && block.x() <= entry.innerMax().x()
                    && block.y() >= entry.innerMin().y() && block.y() <= entry.innerMax().y()
                    && block.z() >= entry.innerMin().z() && block.z() <= entry.innerMax().z()) {
                return true;
            }
        }
        return false;
    }

    static boolean overlapsAny(PortalCandidate candidate, List<PortalIndexEntry> entries) {
        for (PortalIndexEntry entry : entries) {
            if (candidate.upperRight().x() >= entry.innerMin().x() && candidate.lowerLeft().x() <= entry.innerMax().x()
                    && candidate.upperRight().z() >= entry.innerMin().z() && candidate.lowerLeft().z() <= entry.innerMax().z()) {
                return true;
            }
        }
        return false;
    }
}
