package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Server;
import org.bukkit.World.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Production world resolution against the Bukkit registry. Reads only — no chunk or world
 * is created or mutated here.
 *
 * <p>{@code respawnWorld()} prefers the world hosting this backend's own resident dimension
 * over the platform respawn world: every freshly booted backend loads all three vanilla
 * dimension stems, so a nether or end backend carries a stray overworld-typed level world
 * that {@code Server#getRespawnWorld()} points at. Mirage (void stub) worlds match the
 * environment too and are never eligible. When the own environment can not be determined or
 * no resident world exists (stem disabled everywhere, mirage-only), the platform respawn
 * world is used — the pre-existing behaviour.
 *
 * <p>Note: {@code Bukkit.getWorld} is a registry lookup; it is called from the async
 * spawn event as the alternative (blocking the configuration phase on a global-region
 * hop) is strictly worse. No world state is touched.
 */
public final class BukkitWorldLookup implements ArrivalWorldLookup {

    private final Supplier<Environment> ownEnvironment;
    private final Predicate<World> isMirage;

    /** Legacy resolution: platform respawn world, then first loaded world. */
    public BukkitWorldLookup() {
        this(() -> null, world -> false);
    }

    /**
     * Resolution aware of this backend's own resident dimension.
     *
     * @param ownEnvironment supplies this backend's own dimension environment (typically a
     *                       constant resolved once at enable time); may return null when the
     *                       slot can not be resolved — the legacy chain applies then
     */
    public BukkitWorldLookup(Supplier<Environment> ownEnvironment) {
        this(ownEnvironment, world -> false);
    }

    /**
     * Resolution aware of this backend's own resident dimension and its mirage worlds.
     *
     * @param ownEnvironment supplies this backend's own dimension environment; may return null
     * @param isMirageWorld  true for mirage (void stub) worlds, which must never be chosen as
     *                       an arrival placement even though their environment matches
     */
    public BukkitWorldLookup(@Nullable Supplier<Environment> ownEnvironment, @Nullable Predicate<World> isMirageWorld) {
        this.ownEnvironment = ownEnvironment == null ? () -> null : ownEnvironment;
        this.isMirage = isMirageWorld == null ? world -> false : isMirageWorld;
    }

    @Override
    @Nullable
    public World byName(String levelName) {
        return Bukkit.getWorld(levelName);
    }

    @Override
    @Nullable
    public World respawnWorld() {
        Server server = Bukkit.getServer();
        return RespawnWorldSelection.resolve(server.getWorlds(),
                RespawnWorldSelection.safeGet(ownEnvironment), isMirage, server.getRespawnWorld());
    }
}
