package kz.bejiihiu.dimensions.paper.chunktickets;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.UUID;

/**
 * {@link ChunkTicketGateway} backed by the plugin ticket API of the running server.
 * The world key must be the world's UID string; the world is resolved lazily per call
 * so a missing (unloaded) world is reported as "not handled" instead of crashing.
 */
final class WorldChunkTicketGateway implements ChunkTicketGateway {

    private final Plugin plugin;

    WorldChunkTicketGateway(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean add(TicketKey key) {
        World world = resolve(key);
        if (world == null) {
            return false;
        }
        // false = ticket already present for this plugin; that is fine for refresh flows.
        world.addPluginChunkTicket(key.chunkX(), key.chunkZ(), plugin);
        return true;
    }

    @Override
    public boolean remove(TicketKey key) {
        World world = resolve(key);
        if (world == null) {
            return false;
        }
        return world.removePluginChunkTicket(key.chunkX(), key.chunkZ(), plugin);
    }

    private static World resolve(TicketKey key) {
        final UUID id;
        try {
            id = UUID.fromString(key.worldKey());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return Bukkit.getWorld(id);
    }
}
