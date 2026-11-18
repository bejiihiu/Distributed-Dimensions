package kz.bejiihiu.dimensions.entities;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the destination world for an environment. Vanilla deployments have exactly
 * one world per environment — the first match wins. When the environment is missing the
 * resolver falls back to the server respawn world (and then the first loaded world) so a
 * mistyped deployment degrades to a spawn-point landing instead of losing the entity.
 */
public final class EntityWorldResolver {

    private EntityWorldResolver() {
    }

    public static Optional<World> forEnvironment(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) {
                return Optional.of(world);
            }
        }
        return fallbackWorld();
    }

    public static Optional<World> fallbackWorld() {
        if (Bukkit.getServer().getRespawnWorld() != null) {
            return Optional.of(Bukkit.getServer().getRespawnWorld());
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? Optional.empty() : Optional.of(worlds.get(0));
    }
}
