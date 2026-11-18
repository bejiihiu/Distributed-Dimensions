package kz.bejiihiu.dimensions.api;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The immediate outcome of a transfer request, as reported by the implementation at request time.
 *
 * <p>This is <em>not</em> the terminal outcome of the transfer. The terminal outcome is always
 * delivered as an event on the backend that terminally records it — see
 * {@code kz.bejiihiu.dimensions.api.event.DimensionTransferCompleteEvent} and
 * {@code kz.bejiihiu.dimensions.api.event.DimensionTransferFailedEvent}.
 *
 * <p>Mutually exclusive shapes:
 *
 * <ul>
 *   <li>{@code ok = true} — {@code error} is {@code null}; the transfer was accepted and is in flight.</li>
 *   <li>{@code ok = false} — {@code error} is mandatory; {@code message} is a human-readable
 *       detail for logs, never parsed by code.</li>
 * </ul>
 *
 * <p>Build via {@link #success(UUID)} / {@link #failure(UUID, TransferError)}; the compact
 * constructor enforces the invariant.
 *
 * <p>The arrival position is intentionally not part of this contract: the requesting backend
 * cannot know it at request time. Arrival details are delivered with the complete event.
 */
public record TransferResult(boolean ok, UUID operationId, @Nullable TransferError error,
                             @Nullable String message) {

    public TransferResult {
        Objects.requireNonNull(operationId, "operationId");
        if (ok) {
            if (error != null) {
                throw new IllegalArgumentException("a successful result must not carry an error code");
            }
        } else if (error == null) {
            throw new IllegalArgumentException("a failed result must carry an error code");
        }
    }

    /** A transfer that was accepted and is now in flight. */
    public static TransferResult success(UUID operationId) {
        return new TransferResult(true, operationId, null, null);
    }

    public static TransferResult failure(UUID operationId, TransferError error) {
        return new TransferResult(false, operationId, Objects.requireNonNull(error, "error"), null);
    }

    public static TransferResult failure(UUID operationId, TransferError error, String message) {
        return new TransferResult(false, operationId, Objects.requireNonNull(error, "error"), message);
    }
}
