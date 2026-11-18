package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.TransferOperation;

/**
 * Decides how the target places the arriving player, given the claimed journal row.
 * Chosen per backend at wiring time (the End module plugs in its respawn-aware resolver;
 * the default puts the player at the journal position verbatim).
 *
 * <p>Implementations may hit the shared database; they are called from the async spawn
 * selection event, never on a region thread.
 */
public interface ArrivalPlanResolver {

    ArrivalPlan resolve(TransferOperation claimedOperation);

    /** Default: the journal position, in the backend's respawn world. */
    static ArrivalPlanResolver journalPosition() {
        return operation -> new ArrivalPlan.Stored(null, operation.targetPosition());
    }
}
