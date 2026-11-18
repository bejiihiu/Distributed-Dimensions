package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

import java.util.Collection;

/**
 * What the Paper-side adapter implements to feed portal-block candidates into
 * {@link PortalSearch}. The adapter walks the destination world's portal regions around the
 * (floored) target — the vanilla approach uses the point-of-interest store, any enumeration with
 * the same guarantees works — and hands out every currently-active {@code NETHER_PORTAL} block it
 * finds.
 *
 * <p>Contract:
 * <ul>
 *   <li>only blocks that are <b>currently</b> nether portal blocks with a horizontal axis property
 *       may be returned — this replaces vanilla's stale-POI filter;</li>
 *   <li>duplicates are fine — they are removed internally;</li>
 *   <li>order is irrelevant;</li>
 *   <li>the returned blocks should cover the full horizontal search area (all blocks within the
 *       radius of the target) but need not be pre-culled by it;</li>
 *   <li>Y range is unrestricted — portals at any height are visible to the search.</li>
 * </ul>
 */
public interface PortalCandidateView {

    /** Every currently-active portal block known to the adapter. Never null. */
    Collection<BlockPos> portalBlocks();
}
