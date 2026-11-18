package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.Position;

/**
 * Where the target backend should place the arriving player. Resolved on the target
 * (it owns the worlds), from the shared journal plus whatever else the wiring allows —
 * never from client input.
 */
public sealed interface ArrivalPlan {

    /**
     * Place the player at a specific position. {@code worldName} is the level-name of the
     * world to place them in; {@code null} means "the backend's respawn world" (the
     * dedicated-dimension main world in a one-dimension-per-backend deployment).
     * A non-resolvable recorded level-name falls back to the respawn world with a logged
     * note — coordinates from another world are never applied.
     */
    record Stored(String worldName, Position position) implements ArrivalPlan {
        public Stored {
            if (worldName != null && (worldName.isBlank() || worldName.length() > 128)) {
                throw new IllegalArgumentException("worldName must be null or a 1..128 char level-name");
            }
            java.util.Objects.requireNonNull(position, "position");
        }
    }

    /**
     * Place the player at the backend's world spawn (the vanilla "no home respawn is
     * set" behaviour): {@code getRespawnWorld().getSpawnLocation()}. Used for the End
     * exit portal when the player has no recorded bed/anchor.
     */
    record WorldSpawn() implements ArrivalPlan {
    }
}
