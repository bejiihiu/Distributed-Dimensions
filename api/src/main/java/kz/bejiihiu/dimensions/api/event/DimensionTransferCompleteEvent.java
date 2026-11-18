package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.TransferCause;

import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on the <strong>target</strong> backend once the transferred player or entity is fully
 * ready on this backend — join synchronization finished and the arrival location is recorded.
 *
 * <p>Ordering contract on the target backend:
 *
 * <pre>{@code
 * PlayerJoinEvent -> (external sync plugin finished) -> DimensionTransferCompleteEvent
 * }</pre>
 *
 * <p>Listeners that need to observe the post-transfer state (inventory re-bound, effects
 * applied, location final) should listen to this event, not to {@code PlayerJoinEvent}. For
 * entity transfers, {@code EntityTransferEvent} fires slightly earlier, right when the entity
 * is materialized on the target.
 *
 * <p>Not cancellable. Dispatch is idempotent per operation: the target backend deduplicates by
 * operation id (completed operations are remembered for a bounded time, ~5 minutes), so a
 * network retry does not produce a second complete event for the same operation.
 *
 * <p>Exactly one of {@code DimensionTransferCompleteEvent} and {@code DimensionTransferFailedEvent}
 * fires per operation.
 *
 * @see #getDurationMillis()
 */
public class DimensionTransferCompleteEvent extends DimensionTransferEvent {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private final long durationMillis;

    /**
     * @param durationMillis wall-clock time from operation creation on the source backend to
     *                       this dispatch, in milliseconds; must be non-negative
     * @throws IllegalArgumentException if {@code durationMillis} is negative
     */
    public DimensionTransferCompleteEvent(@NotNull UUID operationId, @NotNull Entity entity,
                                          @NotNull DimensionId from, @NotNull DimensionId to,
                                          @NotNull TransferCause cause, long durationMillis) {
        super(operationId, entity, from, to, cause);
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be non-negative, got " + durationMillis);
        }
        this.durationMillis = durationMillis;
    }

    /** Wall-clock transfer time in milliseconds: operation creation to this dispatch. */
    public long getDurationMillis() {
        return durationMillis;
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
