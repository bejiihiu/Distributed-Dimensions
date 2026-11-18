package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlan;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalPlanResolver;

/**
 * Placement decider for the nether flow. Portal operations get the journal position — the
 * ideal destination coordinate, already converter-clamped to the <b>default</b> bounds by the
 * source backend — as a provisional spawn; the settle step (see {@link NetherArrivalExecutor})
 * moves the player to the searched/created portal exit moments later, with the destination
 * world's real border applied to the actual search. Everything else delegates to the wrapped
 * resolver (the End respawn flow).
 *
 * <p>Known approximation vs vanilla: the pre-switch clamp uses the default bounds because the
 * source backend cannot read the destination backend's configured border (the schema is
 * frozen; the border is not in it). The search itself happens on the destination backend
 * against its real border, so only a pathologically small custom border set exclusively on
 * the remote backend can shift the ideal spot; documented in the mechanics guide.
 */
public final class NetherArrivalPlanResolver implements ArrivalPlanResolver {

    private final ArrivalPlanResolver delegate;

    public NetherArrivalPlanResolver(ArrivalPlanResolver delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ArrivalPlan resolve(TransferOperation claimedOperation) {
        if (claimedOperation == null || claimedOperation.cause() != TransferCause.NETHER_PORTAL) {
            return delegate.resolve(claimedOperation);
        }
        Position ideal = claimedOperation.targetPosition();
        if (claimedOperation.targetDimension().isNether()) {
            return new ArrivalPlan.Stored(null, clamp(ideal, WorldBorderBounds.DEFAULT_NETHER));
        }
        if (claimedOperation.targetDimension().isOverworld()) {
            return new ArrivalPlan.Stored(null, clamp(ideal, WorldBorderBounds.DEFAULT_OVERWORLD));
        }
        // unknown target dimension: keep the journal position verbatim rather than invent bounds
        return new ArrivalPlan.Stored(null, ideal);
    }

    private static Position clamp(Position position, WorldBorderBounds bounds) {
        return new Position(bounds.clampX(position.x()), position.y(), bounds.clampZ(position.z()),
                position.yaw(), position.pitch());
    }
}
