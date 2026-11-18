package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;

/**
 * The source-portal context computed at the moment of a portal-typed transfer, before the
 * operation id exists. The {@code PortalTransferService} stamps it onto the transfer-context
 * row with the freshly minted operation id (see {@code TransferContext}) — this record never
 * travels on the wire and never reaches the journal.
 *
 * @param axis          the source portal's horizontal axis ({@code HORIZONTAL_AXIS} of its blocks)
 * @param relative      the relative position inside the source portal's inner rectangle,
 *                      hitbox-reduced (vanilla {@code entityPosInPortal}); for players the
 *                      forward offset is always zero
 * @param entityWidth   the hitbox width at event time (players crouch to a smaller height —
 *                      the exact value is carried so the target can map back precisely)
 * @param entityHeight  the hitbox height at event time
 */
public record TransferSourceContext(
        PortalOrientation axis,
        RelativePortalPosition relative,
        double entityWidth,
        double entityHeight) {

    public TransferSourceContext {
        if (axis == null || relative == null) {
            throw new NullPointerException("axis and relative must not be null");
        }
        if (!(entityWidth >= 0.0) || !Double.isFinite(entityWidth)
                || !(entityHeight >= 0.0) || !Double.isFinite(entityHeight)) {
            throw new IllegalArgumentException(
                    "entity dimensions must be finite and >= 0, got " + entityWidth + " x " + entityHeight);
        }
    }
}
