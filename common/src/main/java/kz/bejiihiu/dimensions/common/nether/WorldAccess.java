package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;

/**
 * Read-only view of the world the portal planner may consult. Deliberately platform-free: ints,
 * booleans and {@link BlockPos} only — no Bukkit/Paper types, so a thin adapter can implement this
 * against any world.
 *
 * <p>The unit of truth is the vanilla {@code PortalForcer} checks:
 * <ul>
 *   <li>{@link #bottomY()}, {@link #topY()}, {@link #logicalHeight()} — the dimension's vertical
 *       regime (overworld: -64..320, logical height 384; nether: 0..128, logical height 128).
 *       {@code topY} is exclusive-ish in the sense that the highest placeable block is
 *       {@code min(topY, bottomY + logicalHeight) - 1}, computed by the planner exactly like
 *       vanilla {@code min(getTopY(), getBottomY() + getLogicalHeight()) - 1}.</li>
 *   <li>{@link #motionBlockingTopY(int, int)} — vanilla {@code Heightmap.Type.MOTION_BLOCKING}
 *       semantics: the Y of the first free position above the highest motion-blocking
 *       (or fluid-containing) block at the column, used as the top scan limit.</li>
 *   <li>{@link #isReplaceable(BlockPos)} — vanilla {@code isBlockStateValid}: the block may be
 *       overwritten by the portal build, i.e. its state is replaceable AND its fluid is empty
 *       ({@code state.isReplaceable() && state.getFluidState().isEmpty()}). Air, fire, grass,
 *       tall grass and the like qualify; water, lava and solid blocks do not.</li>
 *   <li>{@link #isSolid(BlockPos)} — vanilla {@code BlockState.isSolid()}: the block blocks
 *       light/movement enough to serve as frame foundation; the row below the portal must be
 *       solid ground.</li>
 * </ul>
 *
 * <p>World-border membership is not part of this interface; it is passed to
 * {@link PortalCreationPlanner#plan} separately as a {@link WorldBorderBounds}.
 */
public interface WorldAccess {

    /** Dimension bottom (inclusive), e.g. -64 (overworld) or 0 (nether). */
    int bottomY();

    /** Dimension top (exclusive), e.g. 320 (overworld) or 128 (nether). */
    int topY();

    /** Logical height of the dimension, e.g. 384 (overworld) or 128 (nether). */
    int logicalHeight();

    /**
     * MOTION_BLOCKING heightmap value for the column: the Y of the first position above the
     * highest motion-blocking or fluid block, or {@code bottomY} for a void column.
     */
    int motionBlockingTopY(int x, int z);

    /** Vanilla replaceable-and-no-fluid check for blocks that may be overwritten by the build. */
    boolean isReplaceable(BlockPos pos);

    /** Vanilla solid check for frame foundations. */
    boolean isSolid(BlockPos pos);
}
