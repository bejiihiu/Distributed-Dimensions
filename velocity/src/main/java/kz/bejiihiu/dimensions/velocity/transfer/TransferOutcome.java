package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.common.model.TransferError;
import org.jetbrains.annotations.Nullable;

/**
 * Cached outcome of a finished transfer operation. Kept in the completed cache for the
 * idempotency window (~10 minutes) so a re-delivered {@code dimension_transfer_request}
 * with the same {@code request_id} is answered with this outcome instead of a second
 * connection attempt.
 *
 * <p>{@code ok = true} means the player reached the target (or was already there).
 * {@code error == null} iff {@code ok}.
 */
public record TransferOutcome(boolean ok, @Nullable TransferError error, @Nullable String message) {

    public TransferOutcome {
        if (ok == (error != null)) {
            throw new IllegalArgumentException("ok=true forbids an error code, ok=false requires one");
        }
    }

    public static TransferOutcome ok(String message) {
        return new TransferOutcome(true, null, message);
    }

    public static TransferOutcome failed(TransferError error, String message) {
        return new TransferOutcome(false, error, message);
    }
}
