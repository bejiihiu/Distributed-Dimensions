package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

/**
 * The outcome of a portal search: which portal was chosen, which block of it the search
 * anchored to, how far away it is, and the exact inner rectangle vanilla would use for the
 * destination placement.
 *
 * @param candidate        the grouped portal instance the search chose (flood-fill bounds)
 * @param selectedBlock    the nearest portal block to the (floored) target — vanilla's POI anchor
 * @param distanceSquared  squared 3D distance from the target block to {@code selectedBlock}
 * @param innerRectangle   the largest rectangle of portal blocks around {@code selectedBlock}
 *                         (vanilla's BlockLocating.getLargestRectangle), used for entity placement
 */
public record PortalSearchResult(PortalCandidate candidate, BlockPos selectedBlock, double distanceSquared, PortalInnerRect innerRectangle) {

    public PortalSearchResult {
        if (candidate == null || selectedBlock == null || innerRectangle == null) {
            throw new NullPointerException("search result fields must not be null");
        }
        if (!Double.isFinite(distanceSquared) || distanceSquared < 0.0) {
            throw new IllegalArgumentException("distanceSquared must be finite and >= 0, got " + distanceSquared);
        }
        if (!candidate.contains(selectedBlock)) {
            throw new IllegalArgumentException("selectedBlock " + selectedBlock + " is not a member of the chosen candidate");
        }
    }
}
