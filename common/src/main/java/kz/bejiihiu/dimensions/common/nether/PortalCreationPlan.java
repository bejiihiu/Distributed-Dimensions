package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.List;

/**
 * The output of the portal creation planner: exactly which blocks to place, where, and which
 * variant of the vanilla algorithm produced the plan. Nothing is placed here — the caller
 * (Paper region scheduler, eventually) executes the plan.
 *
 * @param origin            the lower-left inner corner of the portal (the first portal block);
 *                          the frame's bottom row sits at {@code origin.y() - 1}
 * @param orientation       the plane the portal spans (from the caller's hint, vanilla takes the
 *                          axis of the source portal, defaulting to X)
 * @param blocks            placement order: forced-placement platform and its air pocket first
 *                          (if any), then the 4x5 obsidian frame, then the 2x3 portal blocks
 * @param innerRect         the 2x3 inner rectangle (lower-left == {@code origin})
 * @param forcedPlacement   true when no valid site was found and the vanilla forced placement was used
 * @param obsidianPlatform  true when a 2x3 obsidian platform + air pocket was planned (forced only)
 */
public record PortalCreationPlan(
        BlockPos origin,
        PortalOrientation orientation,
        List<PlannedPortalBlock> blocks,
        PortalInnerRect innerRect,
        boolean forcedPlacement,
        boolean obsidianPlatform) {

    public PortalCreationPlan {
        if (origin == null || orientation == null || blocks == null || innerRect == null) {
            throw new NullPointerException("plan fields must not be null");
        }
        if (forcedPlacement && !obsidianPlatform) {
            // a forced placement always carries the platform
            throw new IllegalArgumentException("forcedPlacement implies obsidianPlatform");
        }
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a creation plan must contain blocks");
        }
        if (innerRect.width() != NetherPortalConstants.PORTAL_INNER_WIDTH
                || innerRect.height() != NetherPortalConstants.PORTAL_INNER_HEIGHT) {
            throw new IllegalArgumentException("created portal inner rect must be 2x3");
        }
    }

    /** The frame position (lower-left corner of the 4x5 frame, at ground level below the portal). */
    public BlockPos frameLowerLeft() {
        return new BlockPos(origin.x() - frameStepX(), origin.y() - 1, origin.z() - frameStepZ());
    }

    private int frameStepX() {
        return orientation == PortalOrientation.X_AXIS ? 1 : 0;
    }

    private int frameStepZ() {
        return orientation == PortalOrientation.X_AXIS ? 0 : 1;
    }
}
