package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.model.Velocity;
import kz.bejiihiu.dimensions.common.nether.NetherPortalConstants;
import kz.bejiihiu.dimensions.common.nether.PortalCandidate;
import kz.bejiihiu.dimensions.common.nether.PortalCandidateGrouper;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlan;
import kz.bejiihiu.dimensions.common.nether.PortalCreationPlanner;
import kz.bejiihiu.dimensions.common.nether.PortalExitPlacement;
import kz.bejiihiu.dimensions.common.nether.PortalExitCalculator;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.PortalSearch;
import kz.bejiihiu.dimensions.common.nether.PortalSearchResult;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;
import kz.bejiihiu.dimensions.common.nether.PlannedPortalBlock;
import kz.bejiihiu.dimensions.common.persistence.PortalIndexEntry;
import kz.bejiihiu.dimensions.compat.CooldownStore;
import kz.bejiihiu.dimensions.compat.PlayerPortalCooldowns;
import kz.bejiihiu.dimensions.paper.scheduler.RegionMath;
import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;
import kz.bejiihiu.dimensions.paper.transfer.ArrivalWorldLookup;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;
import kz.bejiihiu.dimensions.paper.transfer.TransferArrivalService;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The destination-side nether portal pipeline, run between the async spawn-location claim
 * (provisional placement at the journal position) and the join-time position verify:
 *
 * <ol>
 *   <li>Async: query the shared portal index for candidates overlapping the vanilla search
 *       square. The index is a hint; the world is the truth.</li>
 *   <li>Region threads: harvest the portal blocks from the (async) loaded candidate chunks,
 *       or — cold path — spiral-scan the whole search square with a 64-chunk budget
 *       (unlimited when the index is unavailable: full scan, degraded mode).</li>
 *   <li>Entity scheduler: pick the nearest portal by the vanilla 3D squared distance
 *       ({@link PortalSearch#selectForDestination}); on failure, run the vanilla creation
 *       algorithm over gathered column bitsets ({@link PortalCreationPlanner#plan}) and
 *       execute the plan via region tasks.</li>
 * </ol>
 *
 * <p>The player never teleports here — the settling step (position verify in
 * {@code TransferArrivalService}) moves them from the provisional spot to the computed exit
 * position, which is what makes the whole flow safe to run piecemeal across threads. Any
 * unresolved path keeps the journal placement; nothing here can strand the player.
 *
 * <p>Placement semantics: the source portal's relative-position context (fractions across the
 * source inner rectangle plus the source axis) rides the shared database keyed by operation id —
 * the wire request never reaches this backend. When present, the exit re-maps the fractions
 * onto the destination portal's inner rectangle and rotates yaw by 90° when the destination
 * portal's axis differs from the source's (the vanilla 1.21.x bytecode rule); velocity is
 * zero across the connection switch and pitch is preserved either way. When absent — an
 * unreadable scan on the source, an older context row, a non-portal arrival — the placement
 * falls back to the rectangle's bottom-middle with the shared-axis math. Players riding a
 * vehicle are exempt from the portal cooldown (vanilla assembly semantics); everyone else
 * gets the 300 t stamp at arrival. A fully unreadable scan yields the provisional placement
 * rather than a blind portal creation.
 */
public final class NetherArrivalExecutor implements TransferArrivalService.PostClaimArrivalStep {

    /** Backstop for hung chunk loads; generous compared to any healthy server. */
    private static final long BACKSTOP_MILLIS = 10_000L;

    /** Stale transfer-context rows older than this are reaped on the next arrival. */
    private static final long CONTEXT_TTL_MILLIS = 10L * 60L * 1000L;

    /** On-demand index fill budget for a search square that came up empty (healthy DB). */
    private static final int COLD_SCAN_CHUNK_CAP = 64;

    /** Player hitbox dimensions used by the exit math (vanilla player dimensions). */
    private static final double PLAYER_WIDTH = 0.6;
    private static final double PLAYER_HEIGHT = 1.8;

    private final TransferBackend backend;
    private final PortalIndexer indexer;
    private final CooldownStore cooldowns;

    public NetherArrivalExecutor(TransferBackend backend, NetherPortalStore portalStore,
                                 CooldownStore cooldowns) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        java.util.Objects.requireNonNull(portalStore, "portalStore");
        this.cooldowns = java.util.Objects.requireNonNull(cooldowns, "cooldowns");
        this.indexer = new PortalIndexer(portalStore, backend.diagnostics());
    }

    @Override
    public boolean appliesTo(TransferOperation operation) {
        return operation != null && operation.cause() == TransferCause.NETHER_PORTAL;
    }

    @Override
    public void run(TransferArrivalService.ArrivalCompletion completion, Player player,
                    ArrivalWorldLookup worlds, Consumer<TransferArrivalService.ArrivalCompletion> done) {
        UUID opId = completion.operationId();
        TransferOperation operation = completion.operation();
        AtomicBoolean settled = new AtomicBoolean(false);
        TaskHandle[] backstop = new TaskHandle[1];
        Consumer<TransferArrivalService.ArrivalCompletion> finish = updated -> {
            if (settled.compareAndSet(false, true)) {
                backend.schedulers().cancel(backstop[0]);
                stampArrivalQuietly(player, opId);
                done.accept(updated != null ? updated : completion);
            }
        };
        backstop[0] = backend.schedulers().runAsyncDelayedFor(BACKSTOP_MILLIS, TimeUnit.MILLISECONDS, () -> {
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + " exceeded the chunk-load budget; keeping the journal placement");
            finish.accept(completion);
        });

        try {
            World world = worlds.respawnWorld();
            if (world == null) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": no destination world resolvable; keeping the journal placement");
                finish.accept(completion);
                return;
            }
            World.Environment env = world.getEnvironment();
            // sanity: the world the spawn lands in must match the operation's target dimension
            DimensionId targetDimension = operation.targetDimension();
            boolean envMatchesTarget = (targetDimension.isNether() && env == World.Environment.NETHER)
                    || (targetDimension.isOverworld() && env == World.Environment.NORMAL);
            if (!envMatchesTarget) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": respawn world environment " + env + " does not match target dimension "
                        + targetDimension + " (misconfigured backend?); keeping the journal placement");
                finish.accept(completion);
                return;
            }
            boolean destIsNether = env == World.Environment.NETHER;
            if (env != World.Environment.NETHER && env != World.Environment.NORMAL) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": destination environment " + env + "; keeping the journal placement");
                finish.accept(completion);
                return;
            }
            DimensionId dimension = destIsNether ? DimensionId.NETHER : DimensionId.OVERWORLD;
            int radius = NetherSearchAreas.searchRadius(destIsNether);
            Position ideal = operation.targetPosition();
            BlockPos idealBlock = NetherSearchAreas.floorToBlock(ideal);
            int[] box = NetherSearchAreas.searchBox(idealBlock, radius);
            WorldBorderBounds border = realBorder(world);

            backend.schedulers().runAsync(() -> {
                Optional<List<PortalIndexEntry>> candidates =
                        indexer.candidates(dimension, box[0], box[1], box[2], box[3]);
                Optional<TransferContext> sourceContext = contextQuietly(opId);
                purgeStaleContextsQuietly();
                backend.schedulers().runOn(player, () -> searchPhase(
                        completion, player, world, ideal, destIsNether, border, radius, idealBlock,
                        dimension, candidates, sourceContext, finish));
            });
        } catch (RuntimeException e) {
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + " failed before the scan (" + e.getMessage() + "); keeping the journal placement");
            finish.accept(completion);
        }
    }

    // ------------------------------------------------------------- search

    private void searchPhase(TransferArrivalService.ArrivalCompletion completion, Player player, World world,
                             Position ideal, boolean destIsNether, WorldBorderBounds border,
                             int radius, BlockPos idealBlock, DimensionId dimension,
                             Optional<List<PortalIndexEntry>> candidates,
                             Optional<TransferContext> sourceContext,
                             Consumer<TransferArrivalService.ArrivalCompletion> finish) {
        UUID opId = completion.operationId();
        List<PortalIndexEntry> queried = candidates.orElse(List.of());
        boolean degraded = candidates.isEmpty();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        List<RegionMath.ChunkCoord> toHarvest;
        if (!degraded && !queried.isEmpty()) {
            Set<RegionMath.ChunkCoord> chunks = new HashSet<>();
            for (PortalIndexEntry entry : queried) {
                chunks.addAll(NetherSearchAreas.chunksOverlapping(
                        entry.innerMin().x(), entry.innerMin().z(), entry.innerMax().x(), entry.innerMax().z()));
            }
            toHarvest = List.copyOf(chunks);
        } else {
            int cap = degraded ? Integer.MAX_VALUE : COLD_SCAN_CHUNK_CAP;
            toHarvest = NetherSearchAreas.spiralChunks(idealBlock, radius, cap);
            if (degraded) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": portal index unavailable; running a full world scan (" + toHarvest.size() + " chunks)");
            }
        }

        if (toHarvest.isEmpty()) {
            // nothing to verify against — run the decision with an empty harvest (it selects
            // nothing and falls through to creation exactly as a no-hit scan would)
            decide(completion, player, world, ideal, destIsNether, border, idealBlock,
                    dimension, queried, new ConcurrentLinkedQueue<>(), false, sourceContext, finish);
            return;
        }

        ConcurrentLinkedQueue<BlockPos> blocks = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(toHarvest.size());
        AtomicBoolean harvestFailed = new AtomicBoolean(false);
        Runnable onHarvestDone = () -> backend.schedulers().runOn(player, () -> decide(
                completion, player, world, ideal, destIsNether, border, idealBlock,
                dimension, queried, blocks, harvestFailed.get(), sourceContext, finish));
        harvestChunks(world, toHarvest, minY, maxY, blocks, remaining, harvestFailed, onHarvestDone);
    }

    private void harvestChunks(World world, List<RegionMath.ChunkCoord> chunks, int minY, int maxY,
                               ConcurrentLinkedQueue<BlockPos> blocks, AtomicInteger remaining,
                               AtomicBoolean harvestFailed, Runnable onHarvestDone) {
        for (RegionMath.ChunkCoord coord : chunks) {
            Runnable decrement = () -> {
                if (remaining.decrementAndGet() == 0) {
                    onHarvestDone.run();
                }
            };
            try {
                world.getChunkAtAsync(coord.x(), coord.z()).whenComplete((chunk, error) -> {
                    if (error != null || chunk == null) {
                        harvestFailed.set(true);
                        backend.diagnostics().accept("nether arrival: chunk " + coord.x() + "," + coord.z()
                                + " failed to load during the portal search");
                        decrement.run();
                        return;
                    }
                    backend.schedulers().runOnRegion(world, coord.x(), coord.z(), () -> {
                        try {
                            blocks.addAll(PortalHarvester.portalBlocksInChunk(
                                    PortalHarvester.snapshotOf(chunk), coord.x(), coord.z(), minY, maxY));
                        } catch (Throwable t) {
                            harvestFailed.set(true);
                            backend.diagnostics().accept("nether arrival: harvest failed at chunk "
                                    + coord.x() + "," + coord.z() + " (" + t + ")");
                        } finally {
                            decrement.run();
                        }
                    });
                });
            } catch (Throwable t) {
                harvestFailed.set(true);
                backend.diagnostics().accept("nether arrival: chunk " + coord.x() + "," + coord.z()
                        + " refused the async load (" + t + ")");
                decrement.run();
            }
        }
    }

    private void decide(TransferArrivalService.ArrivalCompletion completion, Player player, World world,
                        Position ideal, boolean destIsNether, WorldBorderBounds border,
                        BlockPos idealBlock, DimensionId dimension,
                        List<PortalIndexEntry> queried, ConcurrentLinkedQueue<BlockPos> found,
                        boolean harvestFailed,
                        Optional<TransferContext> sourceContext,
                        Consumer<TransferArrivalService.ArrivalCompletion> finish) {
        UUID opId = completion.operationId();
        List<BlockPos> harvested = new ArrayList<>(found);
        if (harvested.isEmpty() && harvestFailed) {
            // the world gave us no answer at all — never create blindly from an empty read
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + ": the portal scan produced no data; keeping the journal placement");
            finish.accept(completion);
            return;
        }

        Optional<PortalSearchResult> selected =
                PortalSearch.selectForDestination(ideal, () -> harvested, destIsNether, border);
        List<PortalCandidate> grouped = PortalCandidateGrouper.group(harvested);
        if (!indexer.degraded()) {
            // reconcile is a database write — never run it on the entity thread
            backend.schedulers().runAsync(() ->
                    indexer.reconcile(dimension, queried, harvested, grouped));
        }

        if (selected.isPresent()) {
            PortalSearchResult result = selected.get();
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + ": found portal block at " + result.selectedBlock()
                    + " (squared distance " + (long) result.distanceSquared() + ")");
            Position placement = exitPlacement(result.innerRectangle(), ideal, sourceContext);
            finish.accept(completionWithPosition(completion, world, placement));
            return;
        }

        createPortalPhase(completion, player, world, ideal, border, idealBlock,
                dimension, sourceContext, finish);
    }

    // ------------------------------------------------------------- creation

    private void createPortalPhase(TransferArrivalService.ArrivalCompletion completion, Player player, World world,
                                   Position ideal, WorldBorderBounds border,
                                   BlockPos idealBlock, DimensionId dimension,
                                   Optional<TransferContext> sourceContext,
                                   Consumer<TransferArrivalService.ArrivalCompletion> finish) {
        UUID opId = completion.operationId();
        int bottomY = world.getMinHeight();
        int topY = world.getMaxHeight();
        ColumnScan scan = new ColumnScan(bottomY, topY, topY - bottomY);
        List<RegionMath.ChunkCoord> chunks =
                NetherSearchAreas.spiralChunks(idealBlock, NetherPortalConstants.CREATION_SEARCH_RADIUS, Integer.MAX_VALUE);
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicBoolean gatherFailed = new AtomicBoolean(false);
        Runnable onGathered = () -> backend.schedulers().runOn(player, () -> {
            if (gatherFailed.get()) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": the creation site scan failed; keeping the journal placement");
                finish.accept(completion);
                return;
            }
            Optional<PortalCreationPlan> plan =
                    PortalCreationPlanner.plan(idealBlock, PortalOrientation.X_AXIS, new OfflineWorldAccess(scan), border);
            if (plan.isEmpty()) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": no portal site and forced placement unavailable; keeping the journal placement");
                finish.accept(completion);
                return;
            }
            PortalCreationPlan chosen = plan.get();
            executeCreationPlan(world, chosen, buildOk -> {
                if (!buildOk) {
                    backend.diagnostics().accept("nether arrival for operation " + opId
                            + ": the portal was not fully built; keeping the journal placement");
                    finish.accept(completion);
                    return;
                }
                Position exit = exitPlacement(chosen.innerRect(), ideal, sourceContext);
                try {
                    List<BlockPos> portalBlocks = chosen.blocks().stream()
                            .filter(block -> block.type() == PlannedPortalBlock.Type.NETHER_PORTAL)
                            .map(PlannedPortalBlock::at)
                            .toList();
                    List<PortalCandidate> discovered = PortalCandidateGrouper.group(portalBlocks);
                    if (!discovered.isEmpty()) {
                        // index write — database call, hop to async
                        backend.schedulers().runAsync(() ->
                                discovered.forEach(candidate -> indexer.insertDiscovered(dimension, candidate)));
                    }
                } catch (RuntimeException e) {
                    backend.diagnostics().accept("nether arrival: indexing the created portal failed ("
                            + e.getMessage() + ")");
                }
                backend.diagnostics().accept("nether arrival for operation " + opId + ": created portal at "
                        + chosen.origin() + (chosen.forcedPlacement() ? " (forced placement)" : ""));
                finish.accept(completionWithPosition(completion, world, exit));
            });
        });
        if (chunks.isEmpty()) {
            onGathered.run();
            return;
        }
        for (RegionMath.ChunkCoord coord : chunks) {
            Runnable decrement = () -> {
                if (remaining.decrementAndGet() == 0) {
                    onGathered.run();
                }
            };
            try {
                world.getChunkAtAsync(coord.x(), coord.z()).whenComplete((chunk, error) -> {
                    if (error != null || chunk == null) {
                        gatherFailed.set(true);
                        backend.diagnostics().accept("nether arrival: chunk " + coord.x() + "," + coord.z()
                                + " failed to load during the creation scan");
                        decrement.run();
                        return;
                    }
                    backend.schedulers().runOnRegion(world, coord.x(), coord.z(), () -> {
                        try {
                            PortalHarvester.gatherColumnBits(world, PortalHarvester.snapshotOf(chunk),
                                    coord.x(), coord.z(), bottomY, topY, scan);
                        } catch (Throwable t) {
                            gatherFailed.set(true);
                            backend.diagnostics().accept("nether arrival: creation scan failed at chunk "
                                    + coord.x() + "," + coord.z() + " (" + t + ")");
                        } finally {
                            decrement.run();
                        }
                    });
                });
            } catch (Throwable t) {
                gatherFailed.set(true);
                decrement.run();
            }
        }
    }

    /**
     * Exit placement for the destination portal. When the source recorded its portal context
     * (see {@code TransferContext}), the traveller's hitbox-reduced fractions across the source
     * rectangle are re-mapped onto the exit rectangle by {@link PortalExitCalculator} —
     * exactly vanilla's placement. Otherwise the historical bottom-middle fallback applies.
     */
    private Position exitPlacement(PortalInnerRect exit, Position ideal,
                                   Optional<TransferContext> sourceContext) {
        return exitPlacementFromContext(exit, ideal, sourceContext).position();
    }

    /**
     * Package-private for unit tests: the full placement computation, context or no context.
     * {@code sourceAxis} comes from the context when present — the exit axis alone cannot
     * decide the yaw rotation — and defaults to the exit axis (shared-axis math, same as
     * the pre-context behaviour) when absent.
     */
    static PortalExitPlacement exitPlacementFromContext(PortalInnerRect exit, Position ideal,
                                                        Optional<TransferContext> sourceContext) {
        RelativePortalPosition relative = sourceContext.map(TransferContext::toRelative)
                .orElse(new RelativePortalPosition(0.5, 0.0, 0.0));
        PortalOrientation sourceAxis = sourceContext.map(TransferContext::sourceAxis)
                .orElse(exit.axis());
        double width = sourceContext.map(TransferContext::entityWidth).orElse(PLAYER_WIDTH);
        double height = sourceContext.map(TransferContext::entityHeight).orElse(PLAYER_HEIGHT);
        Velocity velocity = new Velocity(0.0, 0.0, 0.0);      // the switch drops the incoming momentum
        return PortalExitCalculator.exitPlacement(exit, exit.axis(), sourceAxis, relative,
                velocity, ideal.yaw(), ideal.pitch(), width, height);
    }

    /** Context read with the shared fail-soft contract: any failure falls back to bottom-middle. */
    private Optional<TransferContext> contextQuietly(UUID opId) {
        try {
            return backend.transferContexts().find(opId);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + ": transfer context unreadable (" + e.getMessage()
                    + "); using bottom-middle placement");
            return Optional.empty();
        }
    }

    /** Opportunistic reaping of context rows whose arrival never finalized; cheap on a warm pool. */
    private void purgeStaleContextsQuietly() {
        try {
            backend.transferContexts().deleteOlderThan(java.time.Instant.now()
                    .minusMillis(CONTEXT_TTL_MILLIS));
        } catch (RuntimeException e) {
            backend.diagnostics().accept("nether arrival: transfer context purge failed (" + e.getMessage() + ")");
        }
    }

    /**
     * Stamps the arriving player's PDC with the portal cooldown, on the player's own region.
     * Skipped for players riding a vehicle (vanilla assembly semantics) — and never fatal:
     * a stamp failure leaves the ping-pong protection absent, nothing else.
     */
    private void stampArrivalQuietly(Player player, UUID opId) {
        TaskHandle handle = backend.schedulers().runOn(player, () -> {
            try {
                PlayerPortalCooldowns.stampArrival(cooldowns, player, opId, System.currentTimeMillis());
            } catch (RuntimeException e) {
                backend.diagnostics().accept("nether arrival for operation " + opId
                        + ": could not stamp the portal cooldown (" + e.getMessage() + ")");
            }
        });
        if (handle == null) {
            backend.diagnostics().accept("nether arrival for operation " + opId
                    + ": player retired before the portal cooldown could be stamped");
        }
    }

    /**
     * Applies the plan via region tasks, grouped by chunk. The plan's blocks occupy disjoint
     * cells (forced platform/AIR live in x0..1/y-1..2, frame in x-1/x2 columns and y-1/y3 rows,
     * portal in x0..1/y0..2), so no inter-chunk ordering is required.
     *
     * @param onBuilt called with {@code true} only when every chunk applied its share cleanly
     */
    private void executeCreationPlan(World world, PortalCreationPlan plan, java.util.function.Consumer<Boolean> onBuilt) {
        List<PlannedPortalBlock> blocks = plan.blocks();
        Set<RegionMath.ChunkCoord> chunks = new HashSet<>();
        for (PlannedPortalBlock block : blocks) {
            chunks.add(new RegionMath.ChunkCoord(
                    RegionMath.chunkCoordinate(block.at().x()), RegionMath.chunkCoordinate(block.at().z())));
        }
        if (chunks.isEmpty()) {
            onBuilt.accept(true);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(chunks.size());
        AtomicBoolean buildFailed = new AtomicBoolean(false);
        for (RegionMath.ChunkCoord coord : chunks) {
            try {
                world.getChunkAtAsync(coord.x(), coord.z()).whenComplete((chunk, error) -> {
                    if (error != null || chunk == null) {
                        buildFailed.set(true);
                        backend.diagnostics().accept("nether arrival: chunk " + coord.x() + "," + coord.z()
                                + " failed to load during the portal build; the portal was not completed");
                        if (remaining.decrementAndGet() == 0) {
                            onBuilt.accept(false);
                        }
                        return;
                    }
                    backend.schedulers().runOnRegion(world, coord.x(), coord.z(), () -> {
                        try {
                            for (PlannedPortalBlock block : blocks) {
                                if (RegionMath.chunkCoordinate(block.at().x()) != coord.x()
                                        || RegionMath.chunkCoordinate(block.at().z()) != coord.z()) {
                                    continue;
                                }
                                applyPlannedBlock(world, block, plan.orientation());
                            }
                        } catch (Throwable t) {
                            buildFailed.set(true);
                            backend.diagnostics().accept("nether arrival: the portal build hit an error at chunk "
                                    + coord.x() + "," + coord.z() + " (" + t + ")");
                        } finally {
                            if (remaining.decrementAndGet() == 0) {
                                onBuilt.accept(!buildFailed.get());
                            }
                        }
                    });
                });
            } catch (Throwable t) {
                buildFailed.set(true);
                backend.diagnostics().accept("nether arrival: chunk " + coord.x() + "," + coord.z()
                        + " refused the async load during the build (" + t + ")");
                if (remaining.decrementAndGet() == 0) {
                    onBuilt.accept(false);
                }
            }
        }
    }

    private void applyPlannedBlock(World world, PlannedPortalBlock block, PortalOrientation orientation) {
        Block target = world.getBlockAt(block.at().x(), block.at().y(), block.at().z());
        NetherPortalBuild.Placement placement = NetherPortalBuild.placementOf(block, orientation);
        if (!placement.isPortal()) {
            target.setType(placement.material(), false);
            return;
        }
        Orientable data = (Orientable) Material.NETHER_PORTAL.createBlockData();
        data.setAxis(placement.axis());
        target.setBlockData(data, false);
    }

    // ------------------------------------------------------------- placement math

    private TransferArrivalService.ArrivalCompletion completionWithPosition(
            TransferArrivalService.ArrivalCompletion completion, World world, Position placement) {
        return new TransferArrivalService.ArrivalCompletion(completion.operationId(), completion.operation(),
                new Location(world, placement.x(), placement.y(), placement.z(),
                        (float) placement.yaw(), (float) placement.pitch()));
    }

    private WorldBorderBounds realBorder(World world) {
        org.bukkit.WorldBorder border = world.getWorldBorder();
        try {
            org.bukkit.Location center = border.getCenter();
            return NetherSearchAreas.boundsFromCenterAndSize(center.getX(), center.getZ(), border.getSize());
        } catch (Throwable t) {
            backend.diagnostics().accept("nether arrival: destination world border unreadable (" + t
                    + "); using the vanilla default bounds for this arrival");
            return world.getEnvironment() == World.Environment.NETHER
                    ? WorldBorderBounds.DEFAULT_NETHER
                    : WorldBorderBounds.DEFAULT_OVERWORLD;
        }
    }

}
