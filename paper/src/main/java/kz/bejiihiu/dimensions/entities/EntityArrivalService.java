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

import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;

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
    private final java.util.function.Function<World.Environment, World> worldResolver;

    public EntityArrivalService(EntityBackend backend, EntityTransferMetrics metrics,
            Plugin plugin, Clock clock) {
        this(backend, metrics, plugin, clock,
                environment -> EntityWorldResolver.forEnvironment(environment).orElse(null));
    }

    /** Wiring seam for the destination world lookup — registry-level, no world state. */
    public EntityArrivalService(EntityBackend backend, EntityTransferMetrics metrics,
            Plugin plugin, Clock clock,
            java.util.function.Function<World.Environment, World> worldResolver) {
        this.backend = backend;
        this.metrics = metrics;
        this.placementResolver = new EntityPlacementResolver(backend, metrics);
        this.cooldowns = new PdcCooldownStore(plugin);
        this.clock = clock;
        this.worldResolver = java.util.Objects.requireNonNull(worldResolver, "worldResolver");
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
        // pure Location math only — no world access here; the chunk decides the owning region
        Location hint = hintLocation(destination, arrival);
        if (hint == null) {
            backend.diagnostics().accept("entity op " + opId + " rejected: non-finite arrival coordinates");
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }

        // per-op work (POI scan, exit math, spawn) runs inside the region owning the hint chunk
        EntityNode rootNode = root;
        TaskHandle regionHop;
        try {
            regionHop = backend.schedulers().runOnRegion(hint,
                    () -> settleInRegion(opId, destination, graph, arrival, rootNode));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("entity op " + opId + ": destination region task was refused: " + e.getMessage());
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        if (regionHop == null) {
            // the hop never happened — rows become FAILED (never deleted), the source retracts
            backend.diagnostics().accept("entity op " + opId
                    + ": destination region scheduler returned no task handle; rows marked FAILED");
            failQuietly(opId);
            metrics.increment(EntityTransferMetrics.OP_FAILED);
            return false;
        }
        return true;
    }

    /**
     * Destination-side work on the region thread owning the hint chunk. All world state
     * the placement pipeline touches (POI search, border, spawn location, creation plan
     * reads) runs from here.
     */
    private void settleInRegion(String opId, World destination,
            List<EntityNode> graph, JsonObject arrival, EntityNode root) {
        boolean destinationIsNether = destination.getEnvironment() == World.Environment.NETHER;
        placementResolver.resolve(destination, arrival, destinationIsNether,
                FieldCodec.velocity(root.fields().getAsJsonObject("velocity")),
                FieldCodec.getDouble(root.fields(), "yaw", 0.0),
                FieldCodec.getDouble(root.fields(), "pitch", 0.0),
                placement -> spawnAssembly(opId, destination, placement, graph, arrival));
    }

    private void spawnAssembly(String opId, World world, EntityPlacement placement,
            List<EntityNode> graph, JsonObject arrival) {
        // the spawn must land on the region owning the placement chunk — the placement may
        // come from the portal-creation callback (portal-origin region) or from a fixed
        // anchor (end platform), so hop explicitly instead of spawning inline
        TaskHandle spawnHop;
        try {
            spawnHop = backend.schedulers().runOnRegion(placement.location(),
                    () -> spawnInPlace(opId, world, placement, graph, arrival));
        } catch (RuntimeException e) {
            failSpawnHop(opId, "destination region task for the placement chunk was refused: " + e.getMessage());
            return;
        }
        if (spawnHop == null) {
            failSpawnHop(opId, "destination region scheduler returned no task handle for the placement chunk");
        }
    }

    /**
     * The region hop for the spawn failed — the assembly never went into the world and the
     * rows are never deleted (source retracts on its own timeout). The DB write hops back
     * to async; a region thread never blocks on storage.
     */
    private void failSpawnHop(String opId, String reason) {
        backend.diagnostics().accept("entity op " + opId + ": " + reason + "; rows marked FAILED");
        metrics.increment(EntityTransferMetrics.OP_FAILED);
        backend.schedulers().runAsync(() -> failQuietly(opId));
    }

    private void spawnInPlace(String opId, World world, EntityPlacement placement,
            List<EntityNode> graph, JsonObject arrival) {
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

    private World resolveWorld(String targetDimension) {
        DimensionId dimension = DimensionId.of(targetDimension);
        World.Environment environment;
        if (dimension.equals(DimensionId.NETHER)) {
            environment = World.Environment.NETHER;
        } else if (dimension.equals(DimensionId.THE_END)) {
            environment = World.Environment.THE_END;
        } else {
            environment = World.Environment.NORMAL;
        }
        // registry-level lookup (worlds are global metadata) — no block/POI state touched
        return worldResolver.apply(environment);
    }

    private static @Nullable Location hintLocation(World world, JsonObject arrival) {
        double x = FieldCodec.getDouble(arrival, "x", 100.5);
        double y = FieldCodec.getDouble(arrival, "y", 49.0);
        double z = FieldCodec.getDouble(arrival, "z", 0.5);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            // corrupt payload — NaN/Infinity coordinates reject the op instead of
            // routing the region scheduler off garbage
            return null;
        }
        // y is irrelevant to region routing (chunking uses x/z); the Location rides along
        // purely so the scheduler lands on the right region
        return new Location(world, x, y, z);
    }
}
