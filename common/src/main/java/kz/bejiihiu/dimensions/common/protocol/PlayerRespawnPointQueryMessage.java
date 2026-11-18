package kz.bejiihiu.dimensions.common.protocol;

import kz.bejiihiu.dimensions.common.model.PlayerRespawnQuery;

import java.util.UUID;

/**
 * Proxy → backend: "where is this player's respawn point?" — part of the exit-portal flow
 * (the target backend knows beds/anchors; the proxy must not guess).
 *
 * <pre>{@code
 * {"v":1,"type":"player_respawn_point_query","query_id":"…","player_uuid":"…"}
 * }</pre>
 */
public record PlayerRespawnPointQueryMessage(UUID queryId, UUID playerUuid)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "player_respawn_point_query";

    public PlayerRespawnPointQueryMessage {
        new PlayerRespawnQuery(queryId, playerUuid);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public UUID requestId() {
        return queryId;
    }

    public PlayerRespawnQuery toPlayerRespawnQuery() {
        return new PlayerRespawnQuery(queryId, playerUuid);
    }

    public static PlayerRespawnPointQueryMessage fromPlayerRespawnQuery(PlayerRespawnQuery query) {
        return new PlayerRespawnPointQueryMessage(query.queryId(), query.playerUuid());
    }
}
