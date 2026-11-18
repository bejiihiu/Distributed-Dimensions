package kz.bejiihiu.dimensions.common.nether;

/**
 * Where an entity stands inside a portal rectangle, as fractions/offsets of the rectangle
 * (vanilla {@code NetherPortal.entityPosInPortal}).
 *
 * @param right   0..1 fraction along the portal's width axis, measured from the width axis'
 *                start edge: 0 = hugging the start edge, 1 = hugging the opposite edge. 0.5 when
 *                the entity is wider than the portal.
 * @param up      0..1 fraction from the rectangle's bottom edge. 0.0 when the entity is taller
 *                than the portal.
 * @param forward offset perpendicular to the portal plane, in blocks: 0 = centered in the plane
 *                (the plane block's center), positive toward the portal's facing side.
 */
public record RelativePortalPosition(double right, double up, double forward) {

    public RelativePortalPosition {
        if (!Double.isFinite(right) || !Double.isFinite(up) || !Double.isFinite(forward)) {
            throw new IllegalArgumentException("relative position components must be finite");
        }
    }
}
