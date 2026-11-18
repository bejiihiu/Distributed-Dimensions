package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.nether.NetherCoordinateConverter;
import kz.bejiihiu.dimensions.common.nether.PortalExitCalculator;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.compat.PdcCooldownStore;
import kz.bejiihiu.dimensions.compat.PortalCooldownRules;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Boat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Source-side interception of non-player portal crossings. Vanilla cannot switch servers,
 * so the event is cancelled and the assembly is written into the shared payload store;
 * the target backend picks it up from there (poll or join fast-path).
 *
 * <p>Ordering: runs at {@code HIGH} with {@code ignoreCancelled = true} — protection-style
 * plugins veto first and are honoured; the mirage guards (registered earlier at the same
 * priority) get to refuse mirage destinations before any payload is written.
 *
 * <p>The pipeline: exclusions (wither/dragon/creaking) → health gate → category gates →
 * portal cooldown (player-carried assemblies are exempt) → placement hint → passenger
 * graph → rows → tracking → cancel. Every refusal leaves the entity exactly where it is;
 * no rows are written.
 */
public final class EntityPortalEventListener implements Listener {

    private final EntityBackend backend;
    private final EntityTransferMetrics metrics;
    private final EntityTransferTracker tracker;
    private final java.time.Clock clock;
    private final PdcCooldownStore cooldowns;
    private final Predicate<World> isMirageWorld;

    public EntityPortalEventListener(EntityBackend backend, EntityTransferMetrics metrics,
            EntityTransferTracker tracker, Plugin plugin, java.time.Clock clock,
            Predicate<World> isMirageWorld) {
        this.backend = backend;
        this.metrics = metrics;
        this.tracker = tracker;
        this.clock = clock;
        this.cooldowns = new PdcCooldownStore(plugin);
        this.isMirageWorld = isMirageWorld;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.isCancelled()) {
            // a protection plugin already vetoed — nothing to do here
            return;
        }
        org.bukkit.PortalType portalType = event.getPortalType();
        if (portalType != org.bukkit.PortalType.NETHER && portalType != org.bukkit.PortalType.ENDER) {
            // END_GATEWAY is intra-end and CUSTOM is plugin territory — both stay with vanilla
            return;
        }

        Entity entity = event.getEntity();
        World world = entity.getWorld();
        if (world == null || !entity.isValid()) {
            return;
        }
        // mirage worlds are a stub-landing trap; entities never route out of them here
        if (isMirageWorld.test(world)) {
            return;
        }
        World.Environment environment = world.getEnvironment();
        if (environment != World.Environment.NORMAL && environment != World.Environment.THE_END) {
            // custom dimensions are not routable — vanilla keeps them, we do not interfere
            return;
        }

        DimensionId targetDimension = targetFor(portalType, environment);
        if (targetDimension == null) {
            return;
        }
        String targetServer = backend.isHealthy()
                ? backend.dimensions().serverFor(targetDimension).orElse(null)
                : null;
        if (!backend.isHealthy()) {
            // fail-closed: entity stays where it is; mirage guard (if any) covers the void case
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            return;
        }
        if (targetServer == null) {
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            backend.diagnostics().accept("entity portal crossing refused: no server configured for "
                    + targetDimension.key());
            return;
        }

        if (AssemblyGraph.isExcluded(entity)) {
            // wither / dragon / creaking — vanilla never portals them; nothing to cancel into
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            return;
        }
        if (entity instanceof io.papermc.paper.entity.Leashable leashable && leashable.isLeashed()) {
            // leashed entities do not travel — the leash would have to snap or teleport blindly
            metrics.increment(EntityTransferMetrics.LEASHED);
            backend.diagnostics().accept("leashed entity " + entity.getUniqueId()
                    + " was not transferred through a portal (leash across servers is not supported)");
            return;
        }

        boolean carriesPlayer = AssemblyGraph.assemblyCarriesPlayer(entity);
        if (!categoryAllowed(entity, carriesPlayer)) {
            metrics.increment(EntityTransferMetrics.CAT_DISABLED);
            return;
        }

        // portal cooldown — the same rules vanilla applies (player-carried assemblies exempt)
        if (!carriesPlayer && PortalCooldownRules.isCoolingDown(
                entity, cooldowns.lastPassMillis(entity), clock.millis())) {
            event.setCancelled(true);
            metrics.increment(EntityTransferMetrics.COOLDOWN_BLOCKED);
            return;
        }

        // ---- placement / hint ------------------------------------------------
        TransferIntent intent = buildIntent(world, event, portalType, targetDimension, carriesPlayer);
        if (intent == null) {
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            return;
        }

        // ---- passenger graph → rows ------------------------------------------
        List<EntityNode> graph;
        try {
            graph = AssemblyGraph.build(entity, metrics);
        } catch (ExcludedEntityException e) {
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            return;
        } catch (CodecException e) {
            backend.diagnostics().accept("entity portal crossing refused: " + e.getMessage());
            metrics.increment(EntityTransferMetrics.OP_REFUSED);
            return;
        }
        if (graph.isEmpty()) {
            return;
        }
        // one warning per operation for type(s) we cannot fully serialize — grep-able, not silent
        var degradedTypes = graph.stream()
                .filter(node -> node.kind() == kz.bejiihiu.dimensions.common.model.EntityPayloadKind.UNKNOWN_DEGRADED)
                .map(EntityNode::entityTypeKey)
                .distinct()
                .toList();
        if (!degradedTypes.isEmpty()) {
            backend.diagnostics().accept("entity portal crossing: payload(s) for unsupported type(s) "
                    + String.join(", ", degradedTypes)
                    + " will carry only base fields (type, health, name) — custom NBT is lost");
        }

        String opId = UUID.randomUUID().toString();
        JsonObject arrival = intent.toJson();
        // root node carries everything the target needs to materialize the assembly
        graph.get(0).fields().add("arrival", arrival);

        Instant now = Instant.ofEpochMilli(clock.millis());
        List<kz.bejiihiu.dimensions.common.model.EntityTransferPayload> rows =
                EntityCodec.toRows(graph, opId, targetServer, targetDimension.key(),
                        carriesPlayer ? playerHintUuid(entity) : null, now);

        event.setCancelled(true);   // consume the crossing before any DB work can fail
        metrics.increment(EntityTransferMetrics.OP_STARTED);
        List<Entity> assembly = flatten(entity);

        backend.schedulers().runAsync(() -> {
            try {
                backend.entityPayloads().insertAll(rows);
                tracker.track(opId, assembly, clock.millis());
            } catch (RuntimeException e) {
                // nothing was staged — the entity keeps living in the portal as usual
                backend.diagnostics().accept("entity transfer op " + opId
                        + " failed to stage payloads: " + e.getMessage());
                metrics.increment(EntityTransferMetrics.OP_FAILED);
            }
        });
    }

    // ------------------------------------------------------------------ intent

    /** Placement intent captured at the portal; written into the root node as {@code arrival}. */
    private record TransferIntent(String cause, double x, double y, double z, double yaw, double pitch,
            String sourceAxis, double right, double up, double forward,
            double entityWidth, double entityHeight, boolean zeroForward, boolean hadPlayer) {

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("cause", cause);
            obj.addProperty("x", x);
            obj.addProperty("y", y);
            obj.addProperty("z", z);
            obj.addProperty("yaw", yaw);
            obj.addProperty("pitch", pitch);
            if (sourceAxis != null) {
                obj.addProperty("sourceAxis", sourceAxis);
            }
            obj.addProperty("right", right);
            obj.addProperty("up", up);
            obj.addProperty("forward", forward);
            obj.addProperty("ew", entityWidth);
            obj.addProperty("eh", entityHeight);
            obj.addProperty("zeroForward", zeroForward);
            obj.addProperty("hadPlayer", hadPlayer);
            return obj;
        }
    }

    private TransferIntent buildIntent(World world, EntityPortalEvent event,
            org.bukkit.PortalType portalType, DimensionId targetDimension, boolean carriesPlayer) {
        Entity entity = event.getEntity();
        double ew = entity.getWidth();
        double eh = entity.getHeight();
        boolean zeroForward = entity instanceof LivingEntity || entity instanceof Boat || entity instanceof Minecart;

        World.Environment environment = world.getEnvironment();
        if (portalType == org.bukkit.PortalType.ENDER) {
            if (environment == World.Environment.NORMAL) {
                return new TransferIntent("end_platform", 0, 0, 0, 0, 0, null, 0, 0, 0, ew, eh, zeroForward, carriesPlayer);
            }
            return new TransferIntent("world_spawn", 0, 0, 0, 0, 0, null, 0, 0, 0, ew, eh, zeroForward, carriesPlayer);
        }

        // nether flow — the ideal hint comes from the event's own getTo() when available
        // (it already carries vanilla's scaled+clamped destination), else the converter
        Double[] hint = null;
        Location to = event.getTo();
        if (to != null && Double.isFinite(to.getX()) && Double.isFinite(to.getY()) && Double.isFinite(to.getZ())) {
            hint = new Double[] {to.getX(), to.getY(), to.getZ(), (double) to.getYaw(), (double) to.getPitch()};
        } else {
            Location from = event.getFrom();
            Position source = new Position(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch());
            Position converted = targetDimension == DimensionId.NETHER
                    ? NetherCoordinateConverter.overworldToNether(source, WorldBorderBounds.UNBOUNDED)
                    : NetherCoordinateConverter.netherToOverworld(source, WorldBorderBounds.UNBOUNDED);
            hint = new Double[] {converted.x(), converted.y(), converted.z(), converted.yaw(), converted.pitch()};
        }

        // relative position inside the source portal (for exit-rect mapping on the target)
        String sourceAxis = null;
        double right = 0.5, up = 0.0, forward = 0.0;
        kz.bejiihiu.dimensions.common.model.BlockPos eventBlock = new kz.bejiihiu.dimensions.common.model.BlockPos(
                event.getFrom().getBlockX(), event.getFrom().getBlockY(), event.getFrom().getBlockZ());
        // POI scan on the portal's region thread — same-region reads, neighbours best-effort
        java.util.Optional<PortalInnerRect> sourceRect =
                EntityPortalScanner.sourceRect(world, event.getFrom(), eventBlock);
        if (sourceRect.isPresent()) {
            PortalInnerRect rect = sourceRect.get();
            sourceAxis = rect.axis().name();
            RelativePortalPosition relative = PortalExitCalculator.relativePosition(
                    rect, rect.axis(), positionOf(event.getFrom()), ew, eh, zeroForward);
            right = relative.right();
            up = relative.up();
            forward = relative.forward();
        }
        return new TransferIntent("ideal_hint", hint[0], hint[1], hint[2], hint[3], hint[4],
                sourceAxis, right, up, forward, ew, eh, zeroForward, carriesPlayer);
    }

    private static Position positionOf(Location location) {
        return new Position(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static UUID playerHintUuid(Entity root) {
        java.util.Optional<java.util.UUID> player = root.getPassengers().stream()
                .filter(p -> p instanceof org.bukkit.entity.Player)
                .map(Entity::getUniqueId)
                .findFirst();
        return player.orElse(null);
    }

    private static List<Entity> flatten(Entity root) {
        List<Entity> out = new java.util.ArrayList<>();
        flatten(root, out);
        return out;
    }

    private static void flatten(Entity entity, List<Entity> out) {
        out.add(entity);
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof org.bukkit.entity.Player) {
                continue;   // the player is retired by the player operation, not by us
            }
            flatten(passenger, out);
        }
    }

    private static DimensionId targetFor(org.bukkit.PortalType portalType, World.Environment environment) {
        if (portalType == org.bukkit.PortalType.NETHER) {
            if (environment == World.Environment.NORMAL) {
                return DimensionId.NETHER;
            }
            if (environment == World.Environment.THE_END) {
                return null;   // a nether portal in the end is not a route
            }
            return DimensionId.OVERWORLD;   // nether world → overworld backend
        }
        // ender portals: overworld entry → end backend; end exit → overworld backend (no anchor data for entities)
        if (environment == World.Environment.NORMAL) {
            return DimensionId.THE_END;
        }
        if (environment == World.Environment.THE_END) {
            return DimensionId.OVERWORLD;
        }
        return null;
    }

    private boolean categoryAllowed(Entity entity, boolean carriesPlayer) {
        var settings = backend.config().entities().crossServerTransfer();
        if (!settings.enabled()) {
            return false;
        }
        if (carriesPlayer) {
            return settings.players();
        }
        EntityPayloadKindCompat kind = EntityPayloadKindCompat.of(entity);
        return switch (kind) {
            case ITEM, ARROW -> settings.items();
            case VEHICLE -> settings.vehicles();
            case LIVING -> settings.mobs();
        };
    }

    /** Coarse category of an entity for the config gates. */
    private enum EntityPayloadKindCompat {
        ITEM, ARROW, VEHICLE, LIVING;

        static EntityPayloadKindCompat of(Entity entity) {
            if (entity instanceof org.bukkit.entity.Item) {
                return ITEM;
            }
            if (entity instanceof Projectile) {
                return ARROW;
            }
            if (entity instanceof Boat || entity instanceof Minecart
                    || entity instanceof org.bukkit.entity.AbstractHorse) {
                return VEHICLE;
            }
            return LIVING;
        }
    }
}
