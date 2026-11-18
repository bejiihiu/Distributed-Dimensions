package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.OperationState;
import kz.bejiihiu.dimensions.api.TransferCause;
import kz.bejiihiu.dimensions.api.TransferError;

import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.UUID;

/**
 * Fired exactly once per failed operation, on the backend that terminally recorded the failure.
 *
 * <p>Which backend fires it depends on where the operation died:
 *
 * <ul>
 *   <li><strong>Source backend</strong> — failures detected before or at dispatch: pre-dispatch
 *       validation refused the operation, the proxy could not be reached, the operation timed
 *       out, or the outcome was reported back as failed before the target materialized anything.</li>
 *   <li><strong>Target backend</strong> — failures detected after this backend accepted the
 *       request but could not finish it (e.g. the entity could not be materialized, the target
 *       world is not ready). This is the case most often hit by portal flows.</li>
 * </ul>
 *
 * <p>The event is guaranteed to be delivered even if the player is already offline on the
 * firing backend — {@link #isPlayerOnline()} reports the player's presence at dispatch time.
 * {@code getPlayer()} on this event may return a reference to an offline player; prefer other
 * identifying data (operation id, {@link #getError()}) for bookkeeping.
 *
 * <p>Exactly one of {@code DimensionTransferCompleteEvent} and {@code DimensionTransferFailedEvent}
 * fires per operation. A cancelled {@code DimensionTransferStartEvent} is <em>not</em> a
 * failure and produces no failed event.
 *
 * <p>Not cancellable. Thread context follows the dispatching backend: synchronous on the
 * relevant thread (see {@code DimensionTransferEvent}).
 */
public class DimensionTransferFailedEvent extends DimensionTransferEvent {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final TransferError error;
    private final OperationState phase;
    private final boolean playerOnline;

    /**
     * @param error why the operation failed
     * @param phase the operation state at which the failure was recorded — never
     *              {@link OperationState#COMMITTED} (the operation did not succeed) and never
     *              {@link OperationState#FAILED} (this event marks the transition into it)
     * @param playerOnline whether the player was online on the firing backend at dispatch time;
     *                     always {@code true} for non-player entity transfers
     * @throws IllegalArgumentException if {@code phase} is {@code COMMITTED} or {@code FAILED}
     */
    public DimensionTransferFailedEvent(@NotNull UUID operationId, @NotNull Entity entity,
                                        @NotNull DimensionId from, @NotNull DimensionId to,
                                        @NotNull TransferCause cause,
                                        @NotNull TransferError error, @NotNull OperationState phase,
                                        boolean playerOnline) {
        super(operationId, entity, from, to, cause);
        this.error = Objects.requireNonNull(error, "error");
        this.phase = Objects.requireNonNull(phase, "phase");
        if (phase == OperationState.COMMITTED || phase == OperationState.FAILED) {
            throw new IllegalArgumentException(
                    "phase must be a non-terminal state the failure was detected in, got " + phase);
        }
        this.playerOnline = playerOnline;
    }

    /** Why the operation failed. Never {@code null}. */
    @NotNull
    public TransferError getError() {
        return error;
    }

    /** The operation state at which the failure was recorded. Never {@code COMMITTED} or {@code FAILED}. */
    @NotNull
    public OperationState getPhase() {
        return phase;
    }

    /**
     * Whether the player was online on the firing backend when this event was dispatched.
     * Always {@code true} for non-player entity transfers.
     */
    public boolean isPlayerOnline() {
        return playerOnline;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    /** The handler list for this event type. Required by the Bukkit event machinery. */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}
