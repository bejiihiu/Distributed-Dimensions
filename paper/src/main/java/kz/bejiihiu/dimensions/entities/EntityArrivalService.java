package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import kz.bejiihiu.dimensions.compat.PdcCooldownStore;
import kz.bejiihiu.dimensions.compat.PortalCooldownRules;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Boat;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Target-side arrival: claims pending payload operations for this server, materializes the
 * assembly and acknowledges by deleting the rows. All storage access happens on async
 * threads; world work happens on the owning region thread of the placement location.
 *
 * <p>Two trigger paths feed the same machinery: the global poll (every poll cycle, for
 * everyone and for standalone entities) and the player join fast-path (normal join, for
 * assemblies that travel with the player — claims immediately instead of waiting for the
 * next poll tick).
 */
public final class EntityArrivalService {

    private final EntityBackend backend;
    private final EntityTransferMetrics metrics;
    private final EntityPlacementResolver placementResolver;
    private final PdcCooldownStore cooldowns;
    private final Clock clock;

    public EntityArrivalService(EntityBackend backend, EntityTransferMetrics metrics,
            Plugin plugin, Clock clock) {
        this.backend = backend;
        this.metrics = metrics;
        this.placementResolver = new EntityPlacementResolver(backend, metrics);
        this.cooldowns = new PdcCooldownStore(plugin);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ claim paths

    /** Claim pending ops for this server (poll). Returns the number of ops claimed. */
    public int claimPending(String targetServer) {
        List<String> opIds;
        try {
            opIds = backend.entityPayloads().findPendingOpIds(targetServer, 5);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity payload poll failed: " + e.getMessage());
            return 0;
        }
        int claimed = 0;
        for (String opId : opIds) {
            if (claimOp(opId, targetServer)) {
                claimed++;
            }
        }
        return claimed;
    }

    /** Player join fast-path: claim payloads that ride with the joining player. */
    public int claimForPlayer(UUID player, String targetServer) {
        List<String> opIds;
        try {
            opIds = backend.entityPayloads().findPendingOpIdsForPlayer(player, targetServer, 5);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity payload join-probe failed: " + e.getMessage());
            return 0;
        }
        int claimed = 0;
        for (String opId : opIds) {
            if (claimOp(opId, targetServer)) {
                claimed++;
            }
        }
        return claimed;
    }

    /**
     * Stale-claim sweeper: payloads that were claimed but never finished (target died
     * mid-arrival) become FAILED after {@code olderThanMillis}. The source side retracts.
     */
    public int sweepStaleClaims(long olderThanMillis) {
        try {
            Instant before = Instant.ofEpochMilli(clock.millis() - olderThanMillis);
            int flipped = backend.entityPayloads().expireStaleClaimed(before, Instant.ofEpochMilli(clock.millis()));
            if (flipped > 0) {
                metrics.add(EntityTransferMetrics.OP_FAILED, flipped);
            }
            return flipped;
        } catch (RuntimeException e) {
            backend.diagnostics().accept("stale-claim sweep failed: " + e.getMessage());
            return 0;
        }
    }

    /** Dead-letter reaping: remove FAILED rows older than {@code olderThanMillis}. */
    public int reapDeadLetters(long olderThanMillis) {
        try {
            return backend.entityPayloads().deleteFailedOlderThan(
                    Instant.ofEpochMilli(clock.millis() - olderThanMillis));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("dead-letter reap failed: " + e.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------ claim pipeline

    private boolean claimOp(String opId, String targetServer) {
        final List<EntityTransferPayload> claimed;
        try {
            claimed = backend.entityPayloads().claimOp(opId, targetServer,
                    Instant.ofEpochMilli(clock.millis()));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity op " + opId + ": claim failed: " + e.getMessage());
            return false;
        }
        if (claimed.isEmpty()) {
            // already claimed elsewhere / aborted — not ours to run
            return false;
        }
        final List<EntityNode> graph;
        try {
            graph = EntityCodec.fromRows(claimed);
        } catch (CodecException e) {
            backend.diagnostics().accept("entity op " + opId + " rejected at decode: " + e.getMessage());
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        EntityNode root = graph.get(0);
        JsonObject arrival = root.fields().getAsJsonObject("arrival");
        if (arrival == null) {
            backend.diagnostics().accept("entity op " + opId + " rejected: root node carries no arrival blob");
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        String cause = FieldCodec.getString(arrival, "cause");
        if (cause == null) {
            backend.diagnostics().accept("entity op " + opId + " rejected: arrival blob without cause");
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        String targetDimension = claimed.get(0).targetDimension();
        World destination = resolveWorld(targetDimension);
        if (destination == null) {
            backend.diagnostics().accept("entity op " + opId + " cannot resolve a world for " + targetDimension);
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        boolean destinationIsNether = destination.getEnvironment() == World.Environment.NETHER;
        Location hint = hintLocation(destination, arrival);

        // placement decisions (POI search, portal creation, block placement) all live on
        // the region thread of the hint chunk
        backend.schedulers().runOnRegion(hint, () -> placementResolver.resolve(
                destination, arrival, destinationIsNether,
                FieldCodec.velocity(root.fields().getAsJsonObject("velocity")),
                FieldCodec.getDouble(root.fields(), "yaw", 0.0),
                FieldCodec.getDouble(root.fields(), "pitch", 0.0),
                placement -> spawnAssembly(opId, destination, placement, graph, arrival)));
        return true;
    }

    private void spawnAssembly(String opId, World world, EntityPlacement placement,
            List<EntityNode> graph, JsonObject arrival) {
        boolean zeroForward = FieldCodec.getBoolean(arrival, "zeroForward", true);
        EntitySpawner.SpawnOutcome outcome;
        try {
            outcome = EntitySpawner.spawnGraph(world, placement.location(), graph, metrics);
        } catch (CodecException e) {
            // corrupt field data — rows stay CLAIMED, the stale-claim watchdog marks FAILED
            backend.diagnostics().accept("entity op " + opId + " abandoned at spawn: " + e.getMessage());
            metrics.increment(EntityTransferMetrics.SPAWN_FAILED);
            return;
        }

        stampCooldowns(outcome.spawned(), arrival);

        if (!outcome.complete()) {
            // a partial assembly is in the world — never roll back (the no-duplication rule)
            metrics.increment(EntityTransferMetrics.SPAWN_FAILED);
            backend.diagnostics().accept("entity op " + opId
                    + " spawned incompletely; payload rows stay CLAIMED, watchdog will mark FAILED");
            return;
        }

        // chunk ticket for the arrival chunk (vanilla parity) — before the ack so the
        // rows cannot outlive the ticket obligation
        try {
            backend.chunkTickets().addTicketForBlock(world,
                    placement.location().getBlockX(), placement.location().getBlockZ());
        } catch (RuntimeException e) {
            // tickets are an optimization; the arrival itself is already consistent
            backend.diagnostics().accept("entity op " + opId + ": chunk ticket failed: " + e.getMessage());
        }

        // acknowledge: drop the rows — the source's next sweep retires its copies
        backend.schedulers().runAsync(() -> {
            try {
                backend.entityPayloads().deleteForOp(opId);
                metrics.increment(EntityTransferMetrics.OP_COMPLETED);
            } catch (RuntimeException e) {
                backend.diagnostics().accept("entity op " + opId
                        + ": failed to drop payload rows after spawn: " + e.getMessage());
            }
        });
    }

    private void stampCooldowns(List<Entity> spawned, JsonObject arrival) {
        boolean hadPlayer = FieldCodec.getBoolean(arrival, "hadPlayer", false);
        if (hadPlayer) {
            // vanilla: player-carried assemblies never cool down
            return;
        }
        UUID opOperation = UUID.randomUUID();
        long now = clock.millis();
        for (Entity entity : spawned) {
            if (entity == null) {
                continue;
            }
            if (carriesLivingPlayer(entity)) {
                return;   // defensive — a player somehow made it into the assembly
            }
            long ttl = PortalCooldownRules.cooldownMillis(entity);
            if (ttl < 0) {
                continue;
            }
            try {
                cooldowns.stamp(entity, opOperation, now);
            } catch (RuntimeException e) {
                backend.diagnostics().accept("failed to stamp portal cooldown on "
                        + entity.getUniqueId() + ": " + e.getMessage());
            }
        }
    }

    private static boolean carriesLivingPlayer(Entity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof org.bukkit.entity.Player || carriesLivingPlayer(passenger)) {
                return true;
            }
        }
        return false;
    }

    private void failQuietly(String opId) {
        try {
            backend.entityPayloads().failOp(opId, Instant.ofEpochMilli(clock.millis()));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity op " + opId + ": failed to mark payloads FAILED: " + e.getMessage());
        }
    }

    private static World resolveWorld(String targetDimension) {
        DimensionId dimension = DimensionId.of(targetDimension);
        World.Environment environment;
        if (dimension.equals(DimensionId.NETHER)) {
            environment = World.Environment.NETHER;
        } else if (dimension.equals(DimensionId.THE_END)) {
            environment = World.Environment.THE_END;
        } else {
            environment = World.Environment.NORMAL;
        }
        return EntityWorldResolver.forEnvironment(environment).orElse(null);
    }

    private static Location hintLocation(World world, JsonObject arrival) {
        double x = FieldCodec.getDouble(arrival, "x", 100.5);
        double y = FieldCodec.getDouble(arrival, "y", 49.0);
        double z = FieldCodec.getDouble(arrival, "z", 0.5);
        return new Location(world, x, Math.max(world.getMinHeight(), Math.min(world.getMaxHeight(), y)), z);
    }
}
