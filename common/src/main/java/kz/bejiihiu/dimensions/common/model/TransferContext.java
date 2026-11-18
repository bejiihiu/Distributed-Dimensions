package kz.bejiihiu.dimensions.common.model;

import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row in the transfer-context table — the source portal context captured at the moment
 * a portal-typed transfer was dispatched. The journal row alone cannot carry it (the journal
 * schema is frozen and has no free-form payload column), so the source writes this row next to
 * the journal insert with the same operation id; the target reads it at claim/arrival time to
 * re-apply the player's relative spot inside the destination portal.
 *
 * <p>The row is entity-agnostic: nothing player-specific is stored beyond the diagnostics-only
 * {@code playerUuid}, so the same table can later describe non-player portal passes. Velocity
 * is deliberately absent — the connection switch drops momentum in every direction and the
 * vanilla-frozen behaviour zeroes it.
 */
public record TransferContext(
        UUID operationId,
        @Nullable UUID playerUuid,
        PortalOrientation sourceAxis,
        double fx,
        double fy,
        double forward,
        double entityWidth,
        double entityHeight,
        Instant createdAt) {

    public TransferContext {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceAxis, "sourceAxis");
        Objects.requireNonNull(createdAt, "createdAt");
        // the range check alone rejects NaN and infinities (all comparisons are false)
        if (!(fx >= 0.0 && fx <= 1.0)) {
            throw new IllegalArgumentException("fx must be within [0, 1], got " + fx);
        }
        if (!(fy >= 0.0 && fy <= 1.0)) {
            throw new IllegalArgumentException("fy must be within [0, 1], got " + fy);
        }
        if (!Double.isFinite(forward)) {
            throw new IllegalArgumentException("forward must be finite, got " + forward);
        }
        if (!(entityWidth >= 0.0) || !Double.isFinite(entityWidth)
                || !(entityHeight >= 0.0) || !Double.isFinite(entityHeight)) {
            throw new IllegalArgumentException(
                    "entity dimensions must be finite and >= 0, got " + entityWidth + " x " + entityHeight);
        }
    }

    /** Creates a fresh context row for an operation. */
    public static TransferContext created(UUID operationId, @Nullable UUID playerUuid,
            PortalOrientation sourceAxis, RelativePortalPosition relative,
            double entityWidth, double entityHeight, Instant at) {
        return new TransferContext(operationId, playerUuid, sourceAxis,
                relative.right(), relative.up(), relative.forward(),
                entityWidth, entityHeight, at);
    }

    /** The relative position inside the source portal, for exit-placement math. */
    public RelativePortalPosition toRelative() {
        return new RelativePortalPosition(fx, fy, forward);
    }
}
