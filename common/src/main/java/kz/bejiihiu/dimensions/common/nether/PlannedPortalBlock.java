package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

/**
 * One block the portal creation plan will place, and what goes there.
 *
 * @param at   absolute block position
 * @param type OBSIDIAN for frame/platform blocks, AIR for cleared space in forced placements,
 *             NETHER_PORTAL for the two-by-three inner blocks (axis from the plan's orientation)
 */
public record PlannedPortalBlock(BlockPos at, PlannedPortalBlock.Type type) {

    public PlannedPortalBlock {
        if (at == null || type == null) {
            throw new NullPointerException("planned block fields must not be null");
        }
    }

    public enum Type {
        OBSIDIAN,
        AIR,
        NETHER_PORTAL
    }
}
