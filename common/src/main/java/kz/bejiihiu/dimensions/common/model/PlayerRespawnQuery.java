package kz.bejiihiu.dimensions.common.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Proxy -> backend query: "where is player X's respawn point?". Used by the exit-portal flow
 * when the respawn location is not known ahead of time. {@code queryId} correlates the answer.
 */
public record PlayerRespawnQuery(UUID queryId, UUID playerUuid) {

    public PlayerRespawnQuery {
        Objects.requireNonNull(queryId, "queryId");
        Objects.requireNonNull(playerUuid, "playerUuid");
    }
}
