package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Portal creation planning — the vanilla "create a portal if none exists in range" algorithm
 * (PortalForcer.createPortal in the decompiled 1.21.x sources), reduced to a pure computation.
 *
 * <p>Phases, in order:
 * <ol>
 *   <li><b>Site search.</b> A spiral walks the 33x33 square around the target (radius 16), and for
 *       each column scans from {@code min(maxPlaceable, motionBlockingTopY(x, z))} down to the
 *       bottom. A site is the BOTTOM of a vertical run of replaceable blocks; the run is anchored
 *       at its lowest block. The portal needs four replaceable rows above a solid foundation row,
 *       in three perpendicular planes (the portal plane and one block on each side). The nearest
 *       valid site wins by 3D squared distance; a "full" site (all three planes valid) beats a
 *       "partial" site (only the portal plane valid), each nearest-first.</li>
 *   <li><b>Forced placement.</b> When no site exists, vanilla forces a portal at the target with
 *       Y clamped into {@code [max(bottomY + 1, 70), maxPlaceable - 9]} (70..118 in the nether,
 *       70..310 in the overworld), overwriting whatever is there: a 2x3 obsidian platform one
 *       block below the portal's ground row, plus three rows of air above it. The horizontal
 *       position is the target minus one block along the portal's width axis, border-clamped.</li>
 *   <li><b>Frame build.</b> Either way a 4x5 obsidian frame (including corner blocks) is planned
 *       on top of the chosen origin, with the 2x3 portal blocks inside, oriented along the
 *       width axis of {@code orientationHint}.</li>
 * </ol>
 *
 * <p>Orientation hint: vanilla takes the axis from the source portal's block state (default X).
 * The caller decides; pass the source portal's axis, or X for "no source information".
 *
 * <p>Border semantics for the site search match vanilla exactly: the candidate column and the
 * column ONE BLOCK FURTHER along the portal's width axis must both be in the border (the two
 * portal columns the build will use). Forced placement border-clamps its anchor instead of
 * skipping. No world Y constraint from the border.
 */
public final class PortalCreationPlanner {

    // portal width-axis unit vectors (horizontal) and their clockwise neighbours
    private static final int[] EAST = {1, 0, 0};
    private static final int[] SOUTH = {0, 0, 1};
    private static final int[] WEST = {-1, 0, 0};
    private static final int[] NORTH = {0, 0, -1};

    private PortalCreationPlanner() {
    }

    /**
     * Plans a portal creation near {@code target}.
     *
     * @param target         floored destination coordinate (any fractional part is ignored except through block positions)
     * @param orientationHint plane the portal should span; vanilla takes the source portal's axis
     * @param world          read-only world view (height limits, block queries)
     * @param border         destination world border
     * @return the plan, or empty when even forced placement cannot fit (Y range collapsed)
     */
    public static Optional<PortalCreationPlan> plan(BlockPos target, PortalOrientation orientationHint, WorldAccess world, WorldBorderBounds border) {
        if (target == null || orientationHint == null || world == null || border == null) {
            throw new NullPointerException("target, orientationHint, world and border must not be null");
        }

        int[] direction = orientationHint == PortalOrientation.X_AXIS ? EAST : SOUTH;
        int[] side = clockwise(direction);

        // matches vanilla: i = min(getTopY(), getBottomY() + getLogicalHeight()) - 1
        int maxPlaceable = Math.min(world.topY(), world.bottomY() + world.logicalHeight()) - 1;
        int bottomY = world.bottomY();

        double fullDistance = -1.0;
        BlockPos fullSpot = null;
        double partialDistance = -1.0;
        BlockPos partialSpot = null;

        for (BlockPos column : SpiralColumnGenerator.spiral(target, NetherPortalConstants.CREATION_SEARCH_RADIUS)) {
            // the frame will occupy [column - direction ... column - direction + 1]; vanilla checks
            // the spiral column and the one step further along it
            if (!border.containsBlock(column.x(), column.z())
                    || !border.containsBlock(column.x() + direction[0], column.z() + direction[2])) {
                continue;
            }
            int top = Math.min(maxPlaceable, world.motionBlockingTopY(column.x(), column.z()));
            int y = top;
            while (y >= bottomY) {
                if (world.isReplaceable(new BlockPos(column.x(), y, column.z()))) {
                    int runTop = y;
                    // walk down to the bottom of the replaceable run; the portal anchors there
                    while (y > bottomY && world.isReplaceable(new BlockPos(column.x(), y - 1, column.z()))) {
                        y--;
                    }
                    if (y + 4 <= maxPlaceable) {
                        int runLengthMinusOne = runTop - y;
                        if (runLengthMinusOne <= 0 || runLengthMinusOne >= 3) {
                            BlockPos at = new BlockPos(column.x(), y, column.z());
                            if (canHostFrame(at, direction, side, 0, world)) {
                                double d = squaredDistance(target, at);
                                if (canHostFrame(at, direction, side, -1, world) && canHostFrame(at, direction, side, 1, world)
                                        && (fullSpot == null || d < fullDistance)) {
                                    fullSpot = at;
                                    fullDistance = d;
                                }
                                if (fullSpot == null && (partialSpot == null || d < partialDistance)) {
                                    partialSpot = at;
                                    partialDistance = d;
                                }
                            }
                        }
                    }
                }
                y--;
            }
        }

        BlockPos spot = fullSpot != null ? fullSpot : partialSpot;
        boolean forced = spot == null;
        List<PlannedPortalBlock> blocks = new ArrayList<>();

        if (forced) {
            int minStart = Math.max(bottomY + 1, NetherPortalConstants.FORCED_PLACEMENT_MIN_Y);
            int maxStart = maxPlaceable - NetherPortalConstants.FORCED_PLACEMENT_TOP_MARGIN;
            if (maxStart < minStart) {
                // dimension too shallow to force a portal — vanilla logs and gives up
                return Optional.empty();
            }
            int clampedY = clamp(target.y(), minStart, maxStart);
            int clampedX = (int) Math.floor(border.clampX(target.x() - direction[0]));
            int clampedZ = (int) Math.floor(border.clampZ(target.z() - direction[2]));
            spot = new BlockPos(clampedX, clampedY, clampedZ);
            // vanilla: a 2x3 obsidian platform one block below the portal's foundation row plus
            // three rows of air above it, OVERWRITING whatever is there
            for (int sideOffset = -1; sideOffset <= 1; sideOffset++) {
                for (int width = 0; width <= 1; width++) {
                    for (int height = -1; height <= 2; height++) {
                        BlockPos at = new BlockPos(
                                spot.x() + direction[0] * width + side[0] * sideOffset,
                                spot.y() + height,
                                spot.z() + direction[2] * width + side[2] * sideOffset);
                        blocks.add(new PlannedPortalBlock(at,
                                height < 0 ? PlannedPortalBlock.Type.OBSIDIAN : PlannedPortalBlock.Type.AIR));
                    }
                }
            }
        }

        // 4x5 frame including corners (obsidian), then the 2x3 portal blocks
        for (int width = -1; width <= 2; width++) {
            for (int height = -1; height <= 3; height++) {
                boolean isFrame = width == -1 || width == 2 || height == -1 || height == 3;
                if (isFrame) {
                    blocks.add(new PlannedPortalBlock(
                            new BlockPos(spot.x() + direction[0] * width, spot.y() + height, spot.z() + direction[2] * width),
                            PlannedPortalBlock.Type.OBSIDIAN));
                }
            }
        }
        for (int width = 0; width <= 1; width++) {
            for (int height = 0; height <= 2; height++) {
                blocks.add(new PlannedPortalBlock(
                        new BlockPos(spot.x() + direction[0] * width, spot.y() + height, spot.z() + direction[2] * width),
                        PlannedPortalBlock.Type.NETHER_PORTAL));
            }
        }

        PortalInnerRect innerRect = new PortalInnerRect(spot, orientationHint,
                NetherPortalConstants.PORTAL_INNER_WIDTH, NetherPortalConstants.PORTAL_INNER_HEIGHT);
        return Optional.of(new PortalCreationPlan(spot, orientationHint, blocks, innerRect, forced, forced));
    }

    private static boolean canHostFrame(BlockPos at, int[] direction, int[] side, int orthogonalOffset, WorldAccess world) {
        for (int width = -1; width <= 2; width++) {
            for (int height = -1; height <= 3; height++) {
                BlockPos pos = new BlockPos(
                        at.x() + direction[0] * width + side[0] * orthogonalOffset,
                        at.y() + height,
                        at.z() + direction[2] * width + side[2] * orthogonalOffset);
                if (height < 0) {
                    // foundation row must be solid ground
                    if (!world.isSolid(pos)) {
                        return false;
                    }
                } else if (!world.isReplaceable(pos)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[] clockwise(int[] direction) {
        if (direction == EAST) {
            return SOUTH;
        }
        if (direction == SOUTH) {
            return WEST;
        }
        if (direction == WEST) {
            return NORTH;
        }
        return EAST; // NORTH -> EAST, mirroring vanilla rotateYClockwise
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        double dx = (double) a.x() - b.x();
        double dy = (double) a.y() - b.y();
        double dz = (double) a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
