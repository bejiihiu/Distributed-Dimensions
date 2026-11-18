package kz.bejiihiu.dimensions.api.event;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.TransferCause;

import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Fired on the <strong>source</strong> backend immediately before a transfer request is sent
 * to the proxy. This is the only cancellable stage of the transfer pipeline.
 *
 * <p>Dispatched synchronously by the plugin, strictly after the triggering interaction is
 * accepted (portal entry detected, respawn handled, or {@code TransferManager#transfer} called)
 * and strictly before any disconnect or proxy request happens. If a listener cancels this event,
 * the operation never starts: no request is sent, the player/entity stays exactly where it is,
 * and no further events for this operation are fired. Cancelling here is the supported way to
 * veto a transfer programmatically, including mirage-guard teleport transfers
 * ({@link TransferCause#MIRAGE_TELEPORT}).
 *
 * <p>A cancelled start does <em>not</em> produce a {@code DimensionTransferFailedEvent} —
 * cancellation is not a failure.
 *
 * <p>Thread context: synchronous, on the triggering thread of the source backend — for portal
 * flows, the region thread of the portal block (Folia); for entity flows, the entity's owning
 * thread; for programmatic calls, the calling thread. Listeners must not perform blocking I/O.
 *
 * <p>The public constructor exists so the plugin (and tests) can dispatch the event; third-party
 * plugins have no reason to construct it — a constructed event that is never passed to the
 * plugin manager notifies nothing.
 */
public class DimensionTransferStartEvent extends DimensionTransferEvent implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    private boolean cancelled;

    public DimensionTransferStartEvent(@NotNull UUID operationId, @NotNull Entity entity,
                                       @NotNull DimensionId from, @NotNull DimensionId to,
                                       @NotNull TransferCause cause) {
        super(operationId, entity, from, to, cause);
    }

    /** Whether the transfer was cancelled by a listener. */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /** Cancels (or uncancels) the transfer. Takes effect only while the event is dispatching. */
    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
