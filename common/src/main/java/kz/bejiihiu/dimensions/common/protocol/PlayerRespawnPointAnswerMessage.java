package kz.bejiihiu.dimensions.common.protocol;

import org.jetbrains.annotations.Nullable;
import kz.bejiihiu.dimensions.common.model.PlayerRespawnAnswer;
import kz.bejiihiu.dimensions.common.model.RespawnPoint;

import java.util.UUID;

/**
 * Backend → proxy: "this is where player X respawns", or {@code respawn_point = null} meaning
 * "no bed/anchor known — fall back to the overworld world spawn" (the vanilla default, not an error).
 *
 * <pre>{@code
 * {"v":1,"type":"player_respawn_point_answer",
 *  "query_id":"…",
 *  "respawn_point":{"dimension":"minecraft:overworld",
 *                   "position":{"x":1.0,"y":64.0,"z":2.0,"yaw":0.0,"pitch":0.0},
 *                   "kind":"bed"}}
 * }</pre>
 */
public record PlayerRespawnPointAnswerMessage(UUID queryId, @Nullable RespawnPoint respawnPoint)
        implements ProtocolMessage, IdempotentOperation {

    public static final String TYPE = "player_respawn_point_answer";

    public PlayerRespawnPointAnswerMessage {
        java.util.Objects.requireNonNull(queryId, "queryId");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public UUID requestId() {
        return queryId;
    }

    public PlayerRespawnAnswer toPlayerRespawnAnswer() {
        return respawnPoint != null
                ? PlayerRespawnAnswer.known(queryId, respawnPoint)
                : PlayerRespawnAnswer.unknown(queryId);
    }

    public static PlayerRespawnPointAnswerMessage fromPlayerRespawnAnswer(PlayerRespawnAnswer answer) {
        return new PlayerRespawnPointAnswerMessage(answer.queryId(), answer.respawnPoint());
    }
}
