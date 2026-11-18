package kz.bejiihiu.dimensions.mirage;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import org.jetbrains.annotations.NotNull;

/**
 * Arrival math for teleports and programmatic transfers into foreign dimensions.
 *
 * <p>Rules (the same ones the misfire paths and the public API rely on):
 *
 * <ul>
 *   <li>Horizontal coordinates are clamped to {@code ±29_999_984} (the vanilla world-border
 *       extent; the End outer islands live inside it). The nether 1:8 portal scaling is
 *       deliberately <em>not</em> applied — this is a plugin-driven teleport, not a portal.</li>
 *   <li>Y is clamped to {@code [-64, 319]} (the build height of current releases).</li>
 *   <li>For {@link DimensionId#THE_END} the coordinates carry no terrain information — the
 *       mirage contains nothing and the real End's main island lives around y ≈ 57-66 — so
 *       arrival collapses to the spawn island anchor {@value #END_SPAWN_X}, y={@value
 *       #END_SPAWN_Y}, {@value #END_SPAWN_Z}, preserving yaw/pitch. Falling from an arbitrary
 *       y into the outer void would kill the player on arrival, which is never acceptable as
 *       the default for a cross-server jump.</li>
 * </ul>
 *
 * <p>Clamping is silent by design (any out-of-range input is already garbage for a real
 * world); the debug log of the calling guard records the original values.
 */
public final class ArrivalClamp {

    /** Horizontal world-border extent for overworld/nether/end targets. */
    public static final double MAX_HORIZONTAL = 29_999_984.0;
    public static final double MIN_Y = -64.0;
    public static final double MAX_Y = 319.0;

    // The End main-island band. y=66 is safely above the exit-portal dais without being
    // absurdly tall; documented in docs/mirage-dimensions.md as well.
    public static final double END_SPAWN_X = 0.5;
    public static final double END_SPAWN_Y = 66.0;
    public static final double END_SPAWN_Z = 0.5;

    private ArrivalClamp() {
    }

    /** Clamps the horizontal coordinates into the target border. Y is left untouched. */
    public static double clampHorizontal(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(-MAX_HORIZONTAL, Math.min(MAX_HORIZONTAL, v));
    }

    /** Clamps Y into {@code [-64, 319]}. NaN maps to {@value #MIN_Y}. */
    public static double clampY(double y) {
        if (Double.isNaN(y)) {
            return MIN_Y;
        }
        return Math.max(MIN_Y, Math.min(MAX_Y, y));
    }

    /**
     * Computes the arrival position for a transfer into {@code target}.
     *
     * @param target the canonical dimension of the destination backend
     * @param from   the coordinates the caller requested (e.g. the mirage-teleport target)
     */
    public static @NotNull Position arrival(@NotNull DimensionId target, @NotNull Position from) {
        if (target.isTheEnd()) {
            return new Position(END_SPAWN_X, END_SPAWN_Y, END_SPAWN_Z, from.yaw(), from.pitch());
        }
        return new Position(clampHorizontal(from.x()), clampY(from.y()),
                clampHorizontal(from.z()), from.yaw(), from.pitch());
    }
}
