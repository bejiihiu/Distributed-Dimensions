package kz.bejiihiu.dimensions.paper.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * The sync wait failed before the player's data could be confirmed. The arrival
 * flow must treat the transfer as failed and translate this into its terminal
 * failure path — the orchestration layer turns it into
 * {@code DimensionTransferFailedEvent}.
 */
public final class SyncTimeoutException extends RuntimeException {

    private final UUID player;
    private final TimeoutReason reason;

    public SyncTimeoutException(UUID player, TimeoutReason reason) {
        super("timed out waiting for player " + player + " to be synced (" + reason + ")");
        this.player = Objects.requireNonNull(player, "player");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public UUID player() {
        return player;
    }

    public TimeoutReason reason() {
        return reason;
    }
}
