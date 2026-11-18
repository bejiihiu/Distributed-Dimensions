package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Dispatches the public api events of the transfer family. The contract (api module
 * docs, design-api.md): start fires before any disconnect and is cancellable; complete
 * fires on the target after the arrival is fully wired; exactly one of complete/failed
 * fires per operation.
 *
 * <p>Mappings to {@code kz.bejiihiu.dimensions.api} are one-cursor by wire name; both
 * enums pin the same stable tokens.
 */
public interface TransferEventDispatcher {

    /**
     * Fires {@code DimensionTransferStartEvent} synchronously on the calling thread.
     *
     * @return true when a listener cancelled the transfer — the caller must not send a
     *         request and must not fire a failed event
     */
    boolean fireStart(UUID operationId, Player player,
                      kz.bejiihiu.dimensions.common.model.DimensionId from,
                      kz.bejiihiu.dimensions.common.model.DimensionId to,
                      kz.bejiihiu.dimensions.common.model.TransferCause cause);

    /** Fires {@code DimensionTransferCompleteEvent} on the target. Dispatch is idempotent per op. */
    void fireComplete(UUID operationId, Player player,
                      kz.bejiihiu.dimensions.common.model.DimensionId from,
                      kz.bejiihiu.dimensions.common.model.DimensionId to,
                      kz.bejiihiu.dimensions.common.model.TransferCause cause,
                      long durationMillis);

    /**
     * Fires {@code DimensionTransferFailedEvent}. The backend that terminally recorded
     * the failure fires it. {@code player} may be a stale reference (offline player) —
     * pass {@code playerOnline=false} in that case; {@code null} is allowed but then the
     * api contract ("the entity is required") cannot be met and the event is dropped;
     * the journal row is still marked FAILED, which is why the journal stays the source
     * of truth.
     */
    void fireFailed(UUID operationId, @Nullable Player player,
                    kz.bejiihiu.dimensions.common.model.DimensionId from,
                    kz.bejiihiu.dimensions.common.model.DimensionId to,
                    kz.bejiihiu.dimensions.common.model.TransferCause cause,
                    kz.bejiihiu.dimensions.common.model.TransferError error,
                    kz.bejiihiu.dimensions.common.model.OperationState phase,
                    boolean playerOnline);
}
