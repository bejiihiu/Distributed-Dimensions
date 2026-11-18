package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferRequest;

import java.util.UUID;

/**
 * Backend → proxy: "teleport player X into dimension Y at position Z".
 *
 * <pre>{@code
 * {"v":1,"type":"dimension_transfer_request",
 *  "request_id":"…","player_uuid":"…",
 *  "source_server":"overworld","target_server":"nether",
 *  "source_dimension":"minecraft:overworld","target_dimension":"minecraft:the_nether",
 *  "target_position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},
 *  "cause":"nether_portal"}
 * }</pre>
 *
 * The record validates exactly like {@link TransferRequest} (server names, differing endpoints,
 * resource keys) — decoding is never weaker than construction.
 */
public record DimensionTransferRequestMessage(
        UUID requestId,
        UUID playerUuid,
        String sourceServer,
        String targetServer,
        DimensionId sourceDimension,
        DimensionId targetDimension,
        Position targetPosition,
        TransferCause cause)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "dimension_transfer_request";

    public DimensionTransferRequestMessage {
        // one validation path: the domain type carries every business rule
        new TransferRequest(requestId, playerUuid, sourceServer, targetServer,
                sourceDimension, targetDimension, targetPosition, cause);
    }

    @Override
    public String type() {
        return TYPE;
    }

    public TransferRequest toTransferRequest() {
        return new TransferRequest(requestId, playerUuid, sourceServer, targetServer,
                sourceDimension, targetDimension, targetPosition, cause);
    }

    public static DimensionTransferRequestMessage fromTransferRequest(TransferRequest request) {
        return new DimensionTransferRequestMessage(request.requestId(), request.playerUuid(),
                request.sourceServer(), request.targetServer(),
                request.sourceDimension(), request.targetDimension(),
                request.targetPosition(), request.cause());
    }
}
