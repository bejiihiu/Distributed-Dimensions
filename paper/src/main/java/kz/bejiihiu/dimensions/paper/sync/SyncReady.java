package kz.bejiihiu.dimensions.paper.sync;

import java.util.Objects;
import java.util.UUID;

/**
 * The external sync plugin confirmed the player's data was applied.
 *
 * @param player UUID of the player whose data was applied
 * @param cause  how the confirmation arrived
 */
public record SyncReady(UUID player, SyncCause cause) {

    public SyncReady {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(cause, "cause");
    }
}
