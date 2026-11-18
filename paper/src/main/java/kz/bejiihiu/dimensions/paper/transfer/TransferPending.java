package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One in-flight operation tracked on the source backend purely so late proxy results and
 * the expiry watchdog can still put a {@code Player} object into the api events. The
 * journal row remains the source of truth; this map is a bounded, TTL-capped convenience
 * cache for event construction (piggybacked proxy results may arrive on a different
 * player's connection, so the sink's own player cannot be trusted as the subject).
 */
final class TransferPending {

    // hard cap so a lost result can never pin a Player reference forever; the watchdog
    // terminates the operation long before this elapses
    private static final long MAX_TTL_SECONDS = 2 * 60;

    private final UUID operationId;
    private final Player player;
    private final DimensionId sourceDimension;
    private final DimensionId targetDimension;
    private final TransferCause cause;
    private final Instant expiresAt;

    TransferPending(UUID operationId, Player player,
                    DimensionId sourceDimension, DimensionId targetDimension,
                    TransferCause cause, Instant createdAt) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.player = Objects.requireNonNull(player, "player");
        this.sourceDimension = Objects.requireNonNull(sourceDimension, "sourceDimension");
        this.targetDimension = Objects.requireNonNull(targetDimension, "targetDimension");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.expiresAt = createdAt.plusSeconds(MAX_TTL_SECONDS);
    }

    UUID operationId() {
        return operationId;
    }

    Player player() {
        return player;
    }

    DimensionId sourceDimension() {
        return sourceDimension;
    }

    DimensionId targetDimension() {
        return targetDimension;
    }

    TransferCause cause() {
        return cause;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    boolean isExpired(Instant at) {
        return !expiresAt.isAfter(at);
    }
}
