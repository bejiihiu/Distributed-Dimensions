package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a transfer request as reported by the proxy. Mutually exclusive shapes:
 *
 * <ul>
 *   <li>{@code ok = true} — {@code error} is {@code null}; {@code finalPosition} carries the
 *       confirmed arrival position when the proxy knows it (may be {@code null}).</li>
 *   <li>{@code ok = false} — {@code error} is mandatory; {@code message} is a human-readable
 *       detail for logs, never parsed by code.</li>
 * </ul>
 *
 * Build via {@link #success} / {@link #failure}; the compact constructor enforces the invariant.
 */
public record TransferResult(boolean ok, UUID operationId, @Nullable Position finalPosition,
                             @Nullable TransferError error, @Nullable String message) {

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

    public static TransferResult success(UUID operationId, Position finalPosition) {
        return new TransferResult(true, operationId, finalPosition, null, null);
    }

    public static TransferResult success(UUID operationId) {
        return new TransferResult(true, operationId, null, null, null);
    }

    public static TransferResult failure(UUID operationId, TransferError error, String message) {
        return new TransferResult(false, operationId, null, error, message);
    }

    public static TransferResult failure(UUID operationId, TransferError error) {
        return new TransferResult(false, operationId, null, error, null);
    }
}
