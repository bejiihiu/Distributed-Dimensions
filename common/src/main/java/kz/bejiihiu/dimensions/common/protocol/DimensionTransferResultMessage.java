package kz.bejiihiu.dimensions.common.protocol;

import org.jetbrains.annotations.Nullable;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferResult;

import java.util.UUID;

/**
 * Proxy → backend (or vice versa): "the transfer operation {@code request_id} finished with
 * this outcome."
 *
 * <pre>{@code
 * {"v":1,"type":"dimension_transfer_result",
 *  "request_id":"…","ok":true,"final_position":{…}?,"message":null}
 * }</pre>
 *
 * Shape invariant (enforced by the record and by {@link TransferResult}): {@code ok = true}
 * means no error code; {@code ok = false} requires one.
 */
public record DimensionTransferResultMessage(
        boolean ok,
        UUID requestId,
        @Nullable Position finalPosition,
        @Nullable TransferError error,
        @Nullable String message)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "dimension_transfer_result";

    public DimensionTransferResultMessage {
        new TransferResult(ok, requestId, finalPosition, error, message);
    }

    @Override
    public String type() {
        return TYPE;
    }

    public TransferResult toTransferResult() {
        return new TransferResult(ok, requestId, finalPosition, error, message);
    }

    public static DimensionTransferResultMessage fromTransferResult(TransferResult result) {
        return new DimensionTransferResultMessage(
                result.ok(), result.operationId(), result.finalPosition(), result.error(), result.message());
    }
}
