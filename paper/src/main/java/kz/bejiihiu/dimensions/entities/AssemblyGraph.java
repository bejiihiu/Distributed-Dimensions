package kz.bejiihiu.dimensions.entities;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks the passenger graph of an assembly and turns it into the pre-order node list the
 * payload store expects. Player passengers are <em>not</em> serialized — the player's own
 * transfer operation carries them (and their inventory); the assembly arrives without
 * them and the player re-boards (or rides the independently-spawned vehicle).
 *
 * <p>Cycles in a corrupt passenger graph are cut with a visited set. The depth/node-count
 * ceilings are enforced by the record contract ({@link kz.bejiihiu.dimensions.common.model.EntityTransferSpec}
 * documents the same limits for the wire path).
 */
public final class AssemblyGraph {

    public static final int MAX_DEPTH = 16;
    public static final int MAX_NODES = 64 * 16;

    private AssemblyGraph() {
    }

    /** The root refusal: entities that must never go through the pipeline at all. */
    public static boolean isExcluded(Entity entity) {
        String key = entity.getType().getKey().getKey();
        return key.equals("wither") || key.equals("ender_dragon") || key.equals("creaking");
    }

    /** True when any entity in the graph is a player passenger (cooldown exempt, drops players). */
    public static boolean assemblyCarriesPlayer(Entity root) {
        Set<Entity> seen = new HashSet<>();
        return carriesPlayer(root, seen);
    }

    private static boolean carriesPlayer(Entity entity, Set<Entity> seen) {
        if (!seen.add(entity)) {
            return false;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof HumanEntity || carriesPlayer(passenger, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Build the pre-order node list. Throws {@link CodecException} when the graph exceeds
     * the depth/node ceilings. Players are skipped (and their subtree is never walked).
     */
    public static List<EntityNode> build(Entity root, EntityTransferMetrics metrics) {
        List<EntityNode> nodes = new ArrayList<>();
        Set<Entity> seen = new HashSet<>();
        build(root, -1, 0, nodes, seen, metrics);
        return List.copyOf(nodes);
    }

    private static void build(Entity entity, int parentSeq, int depth,
            List<EntityNode> nodes, Set<Entity> seen, EntityTransferMetrics metrics) {
        if (depth > MAX_DEPTH) {
            throw new CodecException("passenger graph exceeds max depth " + MAX_DEPTH);
        }
        if (!seen.add(entity)) {
            // a passenger cycle — already accounted for once
            return;
        }
        int seq = nodes.size();
        if (nodes.size() >= MAX_NODES) {
            throw new CodecException("assembly exceeds max node count " + MAX_NODES);
        }
        EntitySerializer serializer = SerializerRegistry.forEntity(entity);
        if (isExcluded(entity)) {
            throw new ExcludedEntityException("entity type " + entity.getType().getKey() + " never transfers");
        }
        com.google.gson.JsonObject fields = serializer.read(entity, metrics);
        nodes.add(new EntityNode(SerializerRegistry.kindFor(entity),
                entity.getType().getKey().toString(), parentSeq, depth, fields));
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof HumanEntity) {
                // the player's own operation carries them; the assembly sails on without them
                metrics.increment(EntityTransferMetrics.PLAYER_DROPPED);
                continue;
            }
            build(passenger, seq, depth + 1, nodes, seen, metrics);
        }
    }
}
