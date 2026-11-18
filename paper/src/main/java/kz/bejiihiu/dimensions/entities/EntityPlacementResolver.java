package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;
import kz.bejiihiu.dimensions.common.nether.NetherPortalConstants;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlan;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlanner;
import kz.bejiihiu.dimensions.common.nether.PortalExitCalculator;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.common.nether.PortalSearchResult;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Decides where an assembly lands on the target and with what motion. Consumes the
 * {@code arrival} blob the source wrote into the root node; never touches the DB.
 *
 * <p>NETHER arrivals run the vanilla sequence — POI search against the destination world
 * (radius 16 nether / 128 overworld), then exit-placement math against the winning
 * rectangle; if no portal exists the operation may create one (vanilla 1.21+ behavior,
 * gated by {@code entities.cross-server-transfer.portals-create}), and failing that the
 * assembly spawns at the ideal converted coordinates with no rotation applied. ENDER
 * arrivals use the platform anchor (entry) or the world spawn (exit; entities carry no
 * respawn records).
 *
 * <p>Runs on the caller's region thread; portal creation fans the write-out to the
 * owning regions and re-enters through {@code onReady} when done.
 */
public final class EntityPlacementResolver {

    private final EntityBackend backend;
    private final EntityTransferMetrics metrics;

    public EntityPlacementResolver(EntityBackend backend, EntityTransferMetrics metrics) {
        this.backend = backend;
        this.metrics = metrics;
    }

    /**
     * Resolve the placement. Always completes through {@code onReady} — either inline
     * (portal found / end anchors / ideal hint) or from the portal-creation callback.
     */
    public void resolve(World world, JsonObject arrival, boolean destinationIsNether,
            Vector velocity, double yaw, double pitch,
            Consumer<EntityPlacement> onReady) {
        String cause = FieldCodec.getString(arrival, "cause");
        if ("end_platform".equals(cause)) {
            Location platform = new Location(world, 100.5, 49.0, 0.5, 90.0f, 0.0f);
            onReady.accept(new EntityPlacement(platform, velocity, EntityPlacement.Disposition.END_PLATFORM));
            return;
        }
        if ("world_spawn".equals(cause)) {
            Location spawn = world.getSpawnLocation();
            onReady.accept(new EntityPlacement(spawn, velocity, EntityPlacement.Disposition.WORLD_SPAWN));
            return;
        }
        // nether flow
        double ax = FieldCodec.getDouble(arrival, "x", 0.0);
        double ay = FieldCodec.getDouble(arrival, "y", 64.0);
        double az = FieldCodec.getDouble(arrival, "z", 0.0);
        double ayaw = FieldCodec.getDouble(arrival, "yaw", yaw);
        double apitch = FieldCodec.getDouble(arrival, "pitch", pitch);
        String axisName = FieldCodec.getString(arrival, "sourceAxis");
        PortalOrientation sourceAxis = axisName == null ? null : parseAxis(axisName);
        double right = FieldCodec.getDouble(arrival, "right", 0.5);
        double up = FieldCodec.getDouble(arrival, "up", 0.0);
        double forward = FieldCodec.getDouble(arrival, "forward", 0.0);
        double ew = FieldCodec.getDouble(arrival, "ew", 0.6);
        double eh = FieldCodec.getDouble(arrival, "eh", 1.8);
        boolean zeroForward = FieldCodec.getBoolean(arrival, "zeroForward", true);

        Position ideal = new Position(ax, ay, az, ayaw, apitch);
        WorldBorderBounds border = EntityPortalCreator.border(world);
        int radius = NetherPortalConstants.searchRadiusForDestination(destinationIsNether);
        Optional<PortalSearchResult> found =
                EntityPortalScanner.findPortal(world, ideal, radius, border);
        if (found.isPresent()) {
            onReady.accept(placementFromRect(world, ideal, found.get().innerRectangle(),
                    sourceAxis, right, up, forward, velocity, ew, eh, zeroForward,
                    EntityPlacement.Disposition.PORTAL_FOUND));
            return;
        }

        boolean create = backend.config().entities().crossServerTransfer().portalsCreate();
        if (!create) {
            metrics.increment(EntityTransferMetrics.PORTAL_FIND_FAILED);
            onReady.accept(idealPlacement(world, ideal, velocity));
            return;
        }
        kz.bejiihiu.dimensions.common.model.BlockPos floored = new kz.bejiihiu.dimensions.common.model.BlockPos(
                (int) Math.floor(ax), (int) Math.floor(ay), (int) Math.floor(az));
        Optional<PortalCreationPlan> planOpt = PortalCreationPlanner.plan(floored,
                sourceAxis != null ? sourceAxis : PortalOrientation.X_AXIS,
                EntityPortalCreator.worldAccess(world), border);
        if (planOpt.isEmpty()) {
            metrics.increment(EntityTransferMetrics.PORTAL_CREATE_FAILED);
            onReady.accept(idealPlacement(world, ideal, velocity));
            return;
        }
        PortalCreationPlan plan = planOpt.get();
        EntityPortalCreator.place(backend.schedulers(), world, plan, ok -> {
            if (!ok) {
                metrics.increment(EntityTransferMetrics.PORTAL_CREATE_FAILED);
                onReady.accept(idealPlacement(world, ideal, velocity));
                return;
            }
            metrics.increment(EntityTransferMetrics.PORTAL_CREATED);
            onReady.accept(placementFromRect(world, ideal, plan.innerRect(),
                    sourceAxis, right, up, forward, velocity, ew, eh, zeroForward,
                    EntityPlacement.Disposition.PORTAL_CREATED));
        });
    }

    private EntityPlacement placementFromRect(World world, Position ideal, PortalInnerRect exitRect,
            PortalOrientation sourceAxis, double right, double up, double forward,
            Vector velocity, double ew, double eh, boolean zeroForward, EntityPlacement.Disposition disposition) {
        RelativePortalPosition relative = new RelativePortalPosition(right, up, zeroForward ? 0.0 : forward);
        PortalOrientation effectiveSource = sourceAxis != null ? sourceAxis : exitRect.axis();
        Velocity commonVelocity = new Velocity(velocity.getX(), velocity.getY(), velocity.getZ());
        kz.bejiihiu.dimensions.common.nether.PortalExitPlacement result = PortalExitCalculator.exitPlacement(
                exitRect, exitRect.axis(), effectiveSource, relative, commonVelocity,
                ideal.yaw(), ideal.pitch(), ew, eh);
        Position position = result.position();
        Location location = new Location(world, position.x(), position.y(), position.z(),
                (float) position.yaw(), (float) position.pitch());
        Velocity mapped = result.velocity();
        return new EntityPlacement(location, new Vector(mapped.x(), mapped.y(), mapped.z()),
                disposition);
    }

    private EntityPlacement idealPlacement(World world, Position ideal, Vector velocity) {
        Location location = new Location(world, ideal.x(), ideal.y(), ideal.z(),
                (float) ideal.yaw(), (float) ideal.pitch());
        return new EntityPlacement(location, velocity, EntityPlacement.Disposition.IDEAL_HINT);
    }

    private static PortalOrientation parseAxis(String name) {
        try {
            return PortalOrientation.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
