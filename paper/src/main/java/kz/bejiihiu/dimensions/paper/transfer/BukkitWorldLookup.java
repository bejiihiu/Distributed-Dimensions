package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Server;
import org.jetbrains.annotations.Nullable;

/**
 * Production world resolution against the Bukkit registry. Reads only — no chunk or world
 * is created or mutated here.
 *
 * <p>Note: {@code Bukkit.getWorld} is a registry lookup; it is called from the async
 * spawn event as the alternative (blocking the configuration phase on a global-region
 * hop) is strictly worse. No world state is touched.
 */
public final class BukkitWorldLookup implements ArrivalWorldLookup {

    @Override
    @Nullable
    public World byName(String levelName) {
        return Bukkit.getWorld(levelName);
    }

    @Override
    @Nullable
    public World respawnWorld() {
        Server server = Bukkit.getServer();
        World respawn = server.getRespawnWorld();
        if (respawn != null) {
            return respawn;
        }
        // paranoia: every deployment has a default world; fall back to the first loaded one
        java.util.List<World> worlds = server.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }
}
