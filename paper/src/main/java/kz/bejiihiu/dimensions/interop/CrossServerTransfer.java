package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.api.TransferResult;
import org.jetbrains.annotations.NotNull;

/**
 * SPI for starting a cross-server transfer from this backend. Implemented by the backend
 * plugin; consumed by the mirage teleport guards and the public {@code TransferManager}.
 *
 * <p>Contract (each implementation must hold all of it):
 *
 * <ul>
 *   <li>{@code DimensionTransferStartEvent} is fired on this backend <em>before</em> anything
 *       leaves it; a cancelled start vetoes the whole operation and produces no further events.</li>
 *   <li>The request is dispatched to the proxy exactly once, with an idempotency key minted
 *       for this operation; retries reuse the same key.</li>
 *   <li>Exactly one of {@code DimensionTransferCompleteEvent} (target backend, in its arrival
 *       pipeline) or {@code DimensionTransferFailedEvent} (either backend, on terminal failure)
 *       is fired per operation.</li>
 *   <li>Failures are reported through the returned {@link TransferResult}; callers never
 *       receive exceptions for operational problems (offline proxy, degraded persistence).</li>
 * </ul>
 *
 * <p>This interop interface exists so the mirage (compat D) and API (compat A) layers can
 * request transfers without depending on the portal-transfer machinery; it is also the seam
 * that later waves (nether/end, entities) funnel their dispatches through.
 */
public interface CrossServerTransfer {

    /**
     * Requests the transfer of {@code command.player()} to the backend serving
     * {@code command.targetDimension()}.
     *
     * @return the immediate acceptance outcome; never null. {@code ok = false} means the
     *         request was refused (cancelled start, degraded backend, proxy unreachable);
     *         the player was not moved
     */
    @NotNull TransferResult transfer(@NotNull TransferCommand command);
}
