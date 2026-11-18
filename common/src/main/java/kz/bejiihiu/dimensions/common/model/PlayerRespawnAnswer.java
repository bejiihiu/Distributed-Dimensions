package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Backend -> proxy answer to {@link PlayerRespawnQuery}.
 *
 * <p>A {@code null} {@code respawnPoint} means "we don't know / no bed or anchor is set" —
 * the caller falls back to the vanilla default (overworld world spawn). This is deliberately
 * not an error condition; it is the common case for fresh players.
 */
public record PlayerRespawnAnswer(UUID queryId, @Nullable RespawnPoint respawnPoint) {

    public PlayerRespawnAnswer {
        java.util.Objects.requireNonNull(queryId, "queryId");
    }

    public static PlayerRespawnAnswer known(UUID queryId, RespawnPoint point) {
        return new PlayerRespawnAnswer(queryId, java.util.Objects.requireNonNull(point, "point"));
    }

    public static PlayerRespawnAnswer unknown(UUID queryId) {
        return new PlayerRespawnAnswer(queryId, null);
    }

    public boolean isKnown() {
        return respawnPoint != null;
    }
}
