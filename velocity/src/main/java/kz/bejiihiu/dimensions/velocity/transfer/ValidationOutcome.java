package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.common.model.TransferError;

/**
 * Outcome of the validation pipeline for a single transfer request. Either the request may
 * proceed, or the proxy has a definitive reason to refuse - a refusal always carries the
 * {@code TransferError} the backend should see in the failure result.
 */
public sealed interface ValidationOutcome {

    /** The request may proceed to the idempotency gate. */
    record Passed() implements ValidationOutcome {
        public static final Passed INSTANCE = new Passed();
    }

    /** The request is refused; {@code reason} is human-readable, never parsed by code. */
    record Failed(TransferError error, String reason) implements ValidationOutcome {
    }

    static ValidationOutcome passed() {
        return Passed.INSTANCE;
    }

    static ValidationOutcome failed(TransferError error, String reason) {
        return new Failed(error, reason);
    }
}
