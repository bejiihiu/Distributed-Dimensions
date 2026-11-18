package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * World resolution for arrival placement, abstracted so the claim/plan logic can be
 * exercised without a running server. Production reads {@code Bukkit} (registry reads,
 * no mutation); tests hand a fake with deterministic world identities.
 */
public interface ArrivalWorldLookup {

    /** World by level-name, or null when no world with that name exists on this backend. */
    @Nullable
    World byName(String levelName);

    /** The backend's respawn world (where new/returning players land by default), or null. */
    @Nullable
    World respawnWorld();
}
