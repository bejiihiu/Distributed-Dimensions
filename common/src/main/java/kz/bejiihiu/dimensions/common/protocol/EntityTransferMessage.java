package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.EntityTransferSpec;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend → proxy (then proxy → backend): move an entity assembly. The spec tree carries the
 * whole passenger graph — partial assemblies are never transferred.
 *
 * <pre>{@code
 * {"v":1,"type":"entity_transfer",
 *  "operation_id":"…","source_server":"overworld","target_server":"nether",
 *  "target_dimension":"minecraft:the_nether",
 *  "root":{"entity_uuid":"…","entity_type":"minecraft:minecart",
 *          "position":{…},"velocity":{"x":1.0,"y":0.0,"z":0.0},
 *          "passengers":[…],"cooldown":{"type":"vehicle","ticks_remaining":10}}}
 * }</pre>
 */
public record EntityTransferMessage(
        UUID operationId,
        String sourceServer,
        String targetServer,
        DimensionId targetDimension,
        EntityTransferSpec root)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "entity_transfer";

    public EntityTransferMessage {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(targetDimension, "targetDimension");
        Objects.requireNonNull(root, "root");
        if (!WireFormat.isWellFormedServerName(sourceServer)) {
            throw new IllegalArgumentException("sourceServer must match [A-Za-z0-9_-]{1,64}, got '" + sourceServer + "'");
        }
        if (!WireFormat.isWellFormedServerName(targetServer)) {
            throw new IllegalArgumentException("targetServer must match [A-Za-z0-9_-]{1,64}, got '" + targetServer + "'");
        }
        if (sourceServer.equalsIgnoreCase(targetServer)) {
            throw new IllegalArgumentException("source and target server must differ, got '" + sourceServer + "'");
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
