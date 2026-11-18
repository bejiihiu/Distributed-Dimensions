package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * In-flight transfer waiting for the proxy's {@code dimension_transfer_result}.
 * Lives in memory only — a proxy restart or backend restart loses it, and the
 * journal (durable) is the reconciliation source in that case.
 */
record PendingTransfer(UUID operationId, Player player,
                       DimensionId fromDimension, DimensionId toDimension,
                       Position arrival, TransferCause cause,
                       long createdAtMillis) {

    UUID playerUuid() {
        return player.getUniqueId();
    }
}
