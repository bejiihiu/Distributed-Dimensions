package kz.bejiihiu.dimensions.entities;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Materializes the node graph on the target: spawns every node pre-order and re-mounts
 * passengers in the same order. Spawning uses the pre-spawn consumer, so item stacks,
 * inventory contents, names and poses are applied before the entity ticks a single time.
 *
 * <p>A field-deserialization failure ({@link CodecException}) propagates and abandons the
 * whole assembly — a gutted copy is never materialized. A type that no longer exists, an
 * unknown kind, or a spawn rejection skips just that node and is counted.
 */
public final class EntitySpawner {

    private EntitySpawner() {
    }

    public record SpawnOutcome(List<Entity> spawned, boolean complete) {
    }

    /**
     * Spawn all nodes at {@code location}. The caller runs this on the owning region.
     * Mounting happens after every node exists. Throws {@link CodecException} on corrupt
     * field data — the caller abandons the whole op.
     */
    public static SpawnOutcome spawnGraph(World world, Location location, List<EntityNode> graph,
            EntityTransferMetrics metrics) {
        List<Entity> spawned = new ArrayList<>(graph.size());
        for (EntityNode node : graph) {
            spawned.add(spawnOne(world, location, node, metrics));
        }
        // mount in pre-order: parent always exists by now (root first)
        for (int i = 0; i < graph.size(); i++) {
            EntityNode node = graph.get(i);
            if (node.parentSeq() < 0) {
                continue;
            }
            Entity parent = spawned.get(node.parentSeq());
            Entity child = spawned.get(i);
            if (parent == null || child == null) {
                continue;
            }
            try {
                parent.addPassenger(child);
            } catch (RuntimeException e) {
                // mount failed — entity orphaned but not lost; the assembly still functions
                metrics.increment(EntityTransferMetrics.DEGRADED);
            }
        }
        boolean complete = spawned.stream().allMatch(java.util.Objects::nonNull);
        // nulls mark skipped nodes — List.copyOf would NPE on them and take the ack down with it
        return new SpawnOutcome(java.util.Collections.unmodifiableList(spawned), complete);
    }

    private static Entity spawnOne(World world, Location location, EntityNode node,
            EntityTransferMetrics metrics) {
        EntityType type = resolveType(node.entityTypeKey());
        if (type == null || type == EntityType.PLAYER || !type.isSpawnable()) {
            metrics.increment(EntityTransferMetrics.DEGRADED);
            return null;
        }
        EntitySerializer serializer = SerializerRegistry.forKind(node.kind());
        try {
            Entity entity = world.spawnEntity(location, type,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM, created -> {
                        // pre-add failure aborts the spawn — no gutted copies enter the world
                        serializer.write(created, node.fields(), metrics);
                    });
            // belt and braces: the pre-spawn consumer already applied motion
            if (node.fields().has("velocity")) {
                entity.setVelocity(FieldCodec.velocity(node.fields().getAsJsonObject("velocity")));
            }
            metrics.increment(EntityTransferMetrics.NODE_SPAWNED);
            return entity;
        } catch (IllegalArgumentException | IllegalStateException e) {
            metrics.increment(EntityTransferMetrics.SPAWN_FAILED);
            return null;
        }
    }

    private static EntityType resolveType(String typeKey) {
        org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(typeKey);
        if (key == null) {
            return null;
        }
        return org.bukkit.Registry.ENTITY_TYPE.get(key);
    }
}
