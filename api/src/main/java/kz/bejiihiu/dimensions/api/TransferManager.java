package kz.bejiihiu.dimensions.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Entry point for initiating and observing dimension transfers.
 *
 * <p>Implementations are provided by the Distributed Dimensions backend plugin. Third-party
 * plugins must not implement this interface; they obtain the running implementation via
 * {@link DistributedDimensionsAPI#transfers()}.
 *
 * <p>All methods are safe to call from any thread unless noted otherwise; on Folia, requesting
 * a transfer for an entity still applies the normal rule that the transferring entity must be
 * valid at request time.
 */
public interface TransferManager {

    /**
     * Requests a dimension transfer for a player — the same machinery and guarantees as a
     * portal-initiated transfer, but triggered programmatically (e.g. by a command).
     *
     * <p>The call returns as soon as the request has been validated and enqueued. The transfer
     * itself completes asynchronously; the terminal outcome is delivered as
     * {@code DimensionTransferCompleteEvent} or {@code DimensionTransferFailedEvent} on the
     * backend that terminally records it.
     *
     * <p>Cancellation: if a plugin cancels {@code DimensionTransferStartEvent} (fired on this
     * backend before the request is sent to the proxy), the operation never starts and this
     * method reports {@link TransferResult#ok()} {@code false} with
     * {@link TransferError#REJECTED}.
     *
     * @param player the player to transfer; must be online and valid
     * @param target the dimension to transfer the player into
     * @return the immediate acceptance outcome; never {@code null}
     * @throws IllegalArgumentException if {@code target} does not map to any configured backend
     */
    @NotNull TransferResult transfer(@NotNull Player player, @NotNull DimensionId target);

    /**
     * Requests a dimension transfer for a non-player entity — the same machinery as a
     * portal-initiated entity transfer, triggered programmatically.
     *
     * <p>Full entity-transfer support ships with Phase 5. Implementations are required to fail
     * fast with {@link TransferError#REJECTED} until entity transfers are available, rather
     * than silently accepting a request they cannot honour.
     *
     * <p>The entity is not modified or removed by a successful request; the source entity is
     * removed only after the target backend acknowledges materialization. If the operation fails,
     * the source entity remains untouched.
     *
     * @param entity the entity to transfer; must be valid and not dead
     * @param target the dimension to transfer the entity into
     * @return the immediate acceptance outcome; never {@code null}
     * @throws IllegalArgumentException if {@code target} does not map to any configured backend
     */
    @NotNull TransferResult transfer(@NotNull Entity entity, @NotNull DimensionId target);

    /**
     * Looks up the current lifecycle state of a transfer operation.
     *
     * <p>States advance quickly and may be observed in different states by different backends;
     * terminal states are stable. Completed operations are retained for a bounded time on each
     * backend, after which this method returns {@link Optional#empty()}.
     *
     * @param operationId the id carried by {@link TransferResult#operationId()} and the transfer events
     * @return the current state, or empty if this backend has no record of the operation
     */
    @NotNull Optional<OperationState> status(@NotNull UUID operationId);
}
