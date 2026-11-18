package kz.bejiihiu.dimensions.common.protocol;

import org.jetbrains.annotations.Nullable;
import kz.bejiihiu.dimensions.common.model.TransferError;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend → proxy: outcome of creating one transferred entity.
 *
 * <pre>{@code
 * {"v":1,"type":"entity_transfer_ack",
 *  "operation_id":"…","entity_uuid":"…","outcome":"created","error":null,"message":null}
 * }</pre>
 *
 * Invariants: {@code outcome = failed} requires an {@code error} code; the other outcomes
 * forbid one. {@code already_exists} is the lost-ACK replay path — the target re-reports what
 * it did instead of double-creating, and the source deletes its copy without another round trip.
 */
public record EntityTransferAckMessage(
        UUID operationId,
        UUID entityUuid,
        EntityTransferOutcome outcome,
        @Nullable TransferError error,
        @Nullable String message)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "entity_transfer_ack";

    public EntityTransferAckMessage {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(entityUuid, "entityUuid");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == EntityTransferOutcome.FAILED) {
            Objects.requireNonNull(error, "outcome=failed requires an error code");
        } else if (error != null) {
            throw new IllegalArgumentException("outcome=" + outcome.wireName() + " must not carry an error code");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public UUID requestId() {
        return operationId;
    }
}
