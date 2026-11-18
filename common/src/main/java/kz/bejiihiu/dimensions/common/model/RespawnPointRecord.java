package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One row in the respawn-point store — the durable record of where a specific player
 * should respawn. Ownership model: this record belongs to a player, not to a bed or an
 * anchor; at most one row exists per {@code player_uuid} (upsert by that key).
 *
 * <p>{@code dimension} is the resource key of the <em>world</em> the respawn point
 * targets (e.g. {@code minecraft:the_nether}); {@code world} is the target world's
 * level-name (e.g. {@code world_nether}) — the two are stored separately so a backend
 * can resolve both the routing (dimension) and the exact world (level-name).
 *
 * <p>{@code kind} is always {@link RespawnKind#BED} or {@link RespawnKind#RESPAWN_ANCHOR}:
 * the store never persists {@code WORLD_SPAWN}, which is represented by the absence of a
 * record (the vanilla "fall back to world spawn" behaviour of the respawn pipeline).
 */
public record RespawnPointRecord(
        UUID playerUuid,
        DimensionId dimension,
        String world,
        Position position,
        RespawnKind kind,
        Instant updatedAt) {

    public RespawnPointRecord {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world must not be blank");
        }
        world = world.strip();
        if (world.length() > 128) {
            throw new IllegalArgumentException("world level-name must be at most 128 chars, got " + world.length());
        }
        if (kind == RespawnKind.WORLD_SPAWN) {
            throw new IllegalArgumentException(
                    "respawn records only track bed/anchor points; WORLD_SPAWN is the absence of a record");
        }
    }

    /** The subset of this record a proxy answer needs (no world/timestamp). */
    public RespawnPoint toRespawnPoint() {
        return new RespawnPoint(dimension, position, kind);
    }

    /** Projection for diagnostics; never carries the full row in logs. */
    @Nullable
    public String summary() {
        return kind + " @ " + dimension + "/" + world + " (" + position.x() + ", " + position.y() + ", " + position.z() + ")";
    }
}
