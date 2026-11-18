package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pure selection logic for the backend's respawn world, factored out of
 * {@link BukkitWorldLookup} so it can be exercised without a running server.
 *
 * <p>Every freshly booted backend loads all three vanilla dimension stems, so the
 * level (overworld-typed) world exists even on a nether or end backend. The world
 * that physically hosts this backend's resident dimension is the only sane arrival
 * fallback — when it can be identified. Mirage (void stub) worlds carry a matching
 * {@link Environment} too, so they are always excluded via {@code isMirage}.
 */
final class RespawnWorldSelection {

    private RespawnWorldSelection() {
    }

    /**
     * Resolves the world an arriving player should land in when nothing else applies.
     *
     * <ol>
     *   <li>a loaded world whose environment matches {@code ownEnvironment} and that is
     *       not a mirage (the real resident world);</li>
     *   <li>the platform respawn world (today's behaviour);</li>
     *   <li>the first loaded world (paranoia for empty-respawn-world platforms).</li>
     * </ol>
     *
     * @param loaded        worlds loaded on this backend, in load order
     * @param ownEnvironment this backend's own resident dimension environment, or null when unresolvable
     * @param isMirage      true for mirage (void stub) worlds; never eligible as a placement
     * @param respawnWorld  the platform respawn world (fallback candidate), or null
     * @return the world to use, or null when nothing at all is loaded
     */
    @Nullable
    static World resolve(List<World> loaded, @Nullable Environment ownEnvironment,
            Predicate<World> isMirage, @Nullable World respawnWorld) {
        World own = selectOwn(loaded, ownEnvironment, isMirage);
        if (own != null) {
            return own;
        }
        return legacyFallback(respawnWorld, loaded);
    }

    /** First loaded world with the given environment that is not a mirage, or null. */
    @Nullable
    static World selectOwn(List<World> loaded, @Nullable Environment ownEnvironment,
            Predicate<World> isMirage) {
        if (ownEnvironment == null || loaded == null) {
            return null;
        }
        for (World world : loaded) {
            if (world == null || world.getEnvironment() != ownEnvironment) {
                continue;
            }
            if (isMirage.test(world)) {
                continue;
            }
            return world;
        }
        return null;
    }

    /** The pre-own-dimension behaviour: platform respawn world, then first loaded world. */
    @Nullable
    static World legacyFallback(@Nullable World respawnWorld, List<World> loaded) {
        if (respawnWorld != null) {
            return respawnWorld;
        }
        if (loaded == null || loaded.isEmpty()) {
            return null;
        }
        return loaded.get(0);
    }

    /** Reads {@code ownEnvironment} defensively; a broken supplier degrades to null, never throws. */
    @Nullable
    static Environment safeGet(Supplier<Environment> ownEnvironment) {
        try {
            return ownEnvironment.get();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
