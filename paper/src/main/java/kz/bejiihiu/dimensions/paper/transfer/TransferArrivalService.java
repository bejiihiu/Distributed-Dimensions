package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.paper.scheduler.RegionMath;
import kz.bejiihiu.dimensions.paper.scheduler.TaskHandle;
import kz.bejiihiu.dimensions.paper.sync.SyncOperations;
import kz.bejiihiu.dimensions.paper.sync.SyncReport;
import kz.bejiihiu.dimensions.paper.sync.SyncTimeoutException;
import kz.bejiihiu.dimensions.paper.sync.TimeoutReason;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Target side of a portal-initiated cross-server transfer for a player. One code path
 * serves both End and Nether portals; the Nether flow adds an optional post-claim step
 * (target-side portal search/creation, see {@link PostClaimArrivalStep}) which runs
 * between the sync gate and the placement settle. The arrival flow:
 *
 * <ol>
 *   <li>{@link #claimAndPlan} — called from {@code AsyncPlayerSpawnLocationEvent}
 *       (async). Claims the newest pending operation for the joining player
 *       (CAS {@code CREATED|PREPARING -> TARGET_CREATED}), resolves the arrival plan and
 *       records the placement in a short-lived pending map. Nothing here touches the
 *       world and nothing here waits for the external sync plugin — the provisional
 *       spawn placement is the safety net for slow syncs.</li>
 *   <li>{@link #onJoin} — called from {@code PlayerJoinEvent}. Attaches
 *       {@code SyncOperations.awaitSync(player)}; only when the wait completes normally
 *       does the placement work run — a stored position that no longer passes validation
 *       falls back to world spawn (vanilla missing-respawn-block semantics); anything
 *       materially different from the join placement gets an explicit teleport. The sync
 *       wait never blocks a thread; it is bounded by the integration's own timeout.</li>
 *   <li>Finalization — after sync readiness settles and two entity-scheduler ticks later,
 *       chunk tickets are applied, the journal row advances {@code TARGET_CREATED -> SOURCE_REMOVED ->
 *       COMMITTED} and {@code DimensionTransferCompleteEvent} is dispatched — but only by
 *       the party whose CAS transitions succeeded, so the event fires exactly once.</li>
 * </ol>
 *
 * <p>On sync wait failure ({@link SyncTimeoutException}, or any exceptional future
 * completion) the player is NOT moved — they keep the provisional spawn placement. The
 * operation is terminally failed with the mapped {@code TransferError}, the row carries
 * the reason, a SEVERE line logs player uuid + reason, and
 * {@code DimensionTransferFailedEvent} fires exactly once (with {@code playerOnline=false}
 * when the player already left). {@code DimensionTransferCompleteEvent} never fires for
 * such an operation. If the sync services are entirely missing (plugin disabled or boot
 * failed), the arrival proceeds in degraded mode with a warning — never crashes.
 *
 * <p>If the player never completes the arrival (disconnected during the configuration
 * phase, or before the sync wait settles), a two-timeout watchdog terminally fails the
 * operation as {@link TransferError#TIMEOUT} — the row never lingers non-terminal.
 *
 * <p>Players with no pending operation are unaffected: {@code onJoin} does nothing and
 * vanilla join behaviour stands.
 */
public final class TransferArrivalService {

    /** Entity-scheduler ticks to wait between the placement settle and the journal commit/Complete event. */
    private static final long COMPLETE_EVENT_DELAY_TICKS = 2L;

    /**
     * Optional pre-verify step for arrivals that need target-side work the moment the
     * player is in the world but before the journal position is applied (the Nether flow
     * resolves/creates the destination portal there). The step runs after the join, on the
     * player's scheduler context; it may be asynchronous (chunk loads, database) and MUST
     * call its callback exactly once. Failures must settle — passing {@code null} keeps the
     * journal-position placement.
     *
     * <p>Steps apply only to operations they claim via {@link #appliesTo(TransferOperation)};
     * everything else takes the plain join path unchanged.
     */
    public interface PostClaimArrivalStep {
        boolean appliesTo(TransferOperation operation);

        void run(ArrivalCompletion completion, Player player, ArrivalWorldLookup worlds,
                 Consumer<ArrivalCompletion> done);
    }

    /** What the spawn event should do with the player. */
    public record ArrivalClaim(ArrivalPlan plan, @Nullable Location location) {
    }

    /** What the join path carries forward to finalization. */
    public record ArrivalCompletion(UUID operationId, TransferOperation operation,
                                    @Nullable Location arrivalLocation) {
    }

    private final TransferBackend backend;
    private final Clock clock;
    private final ArrivalPlanResolver plans;
    private final PositionValidator validator;
    private final SyncLookup syncLookup;
    private final @Nullable PostClaimArrivalStep step;
    private final Map<UUID, ArrivalCompletion> pending = new ConcurrentHashMap<>();

    public TransferArrivalService(TransferBackend backend, Clock clock,
                                  ArrivalPlanResolver plans, PositionValidator validator) {
        this(backend, clock, plans, validator, SyncLookup.platform(), null);
    }

    /** Wiring seam for the sync gate — production uses the default platform lookup. */
    public TransferArrivalService(TransferBackend backend, Clock clock,
                                  ArrivalPlanResolver plans, PositionValidator validator,
                                  SyncLookup syncLookup) {
        this(backend, clock, plans, validator, syncLookup, null);
    }

    /** Wiring seam for the post-claim step; the sync gate uses the platform lookup. */
    public TransferArrivalService(TransferBackend backend, Clock clock,
                                  ArrivalPlanResolver plans, PositionValidator validator,
                                  @Nullable PostClaimArrivalStep step) {
        this(backend, clock, plans, validator, SyncLookup.platform(), step);
    }

    /** Both wiring seams in one: sync gate + optional post-claim step. */
    public TransferArrivalService(TransferBackend backend, Clock clock,
                                  ArrivalPlanResolver plans, PositionValidator validator,
                                  SyncLookup syncLookup, @Nullable PostClaimArrivalStep step) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.plans = java.util.Objects.requireNonNull(plans, "plans");
        this.validator = java.util.Objects.requireNonNull(validator, "validator");
        this.syncLookup = java.util.Objects.requireNonNull(syncLookup, "syncLookup");
        this.step = step;
    }

    // ------------------------------------------------------- claim (async)

    /**
     * Claims the newest pending operation for the joining player. Runs in the async spawn
     * selection event; performs registry-only world lookups (never block or entity
     * access) and no dispatch — the listener applies the returned placement to the event.
     */
    public Optional<ArrivalClaim> claimAndPlan(UUID playerUuid, ArrivalWorldLookup worlds) {
        TransferOperation latest = findLatestPending(playerUuid);
        if (latest == null) {
            return Optional.empty();
        }
        Instant claimedAt = Instant.now(clock);
        TransferOperation current = latest;
        // the state machine forbids CREATED -> TARGET_CREATED directly; a claimed-but-never
        // dispatched op steps through PREPARING first (the source lands it there too)
        if (current.state() == OperationState.CREATED) {
            TransferOperation preparing = current.withState(OperationState.PREPARING, claimedAt, null, null);
            if (!backend.journal().compareAndSetState(current.operationId(), OperationState.CREATED, preparing)) {
                backend.diagnostics().accept("operation " + current.operationId()
                        + " advanced concurrently at claim; skipping arrival");
                return Optional.empty();
            }
            current = preparing;
        }
        TransferOperation claimed = current.withState(OperationState.TARGET_CREATED, claimedAt, null, null);
        if (!backend.journal().compareAndSetState(current.operationId(), OperationState.PREPARING, claimed)) {
            // lost the race — the other claimant owns the arrival
            backend.diagnostics().accept("operation " + current.operationId()
                    + " was claimed by another path; skipping arrival");
            return Optional.empty();
        }

        ArrivalPlan plan = plans.resolve(claimed);
        Location location = placementOf(plan, worlds, claimed);

        pending.put(playerUuid, new ArrivalCompletion(claimed.operationId(), claimed, location));
        scheduleClaimTimeout(playerUuid, claimed.operationId());

        return Optional.of(new ArrivalClaim(plan, location));
    }

    private TransferOperation findLatestPending(UUID playerUuid) {
        List<TransferOperation> active = backend.journal().findActiveForPlayer(playerUuid);
        TransferOperation latest = null;
        for (TransferOperation op : active) {
            if (op.state() == OperationState.CREATED || op.state() == OperationState.PREPARING) {
                latest = op;   // list is oldest-first — last match is the newest pending
            }
        }
        return latest;
    }

    private Location placementOf(ArrivalPlan plan, ArrivalWorldLookup worlds, TransferOperation op) {
        if (plan instanceof ArrivalPlan.WorldSpawn) {
            World respawn = worlds.respawnWorld();
            if (respawn == null) {
                backend.diagnostics().accept("no respawn world resolvable for operation " + op.operationId()
                        + "; leaving spawn selection to the server default");
                return null;
            }
            return respawn.getSpawnLocation();
        }
        ArrivalPlan.Stored stored = (ArrivalPlan.Stored) plan;
        World world = null;
        if (stored.worldName() != null) {
            world = worlds.byName(stored.worldName());
            if (world == null) {
                // the recorded world does not exist on this backend — coordinates from it are
                // meaningless here, so fall back to the backend's world spawn entirely
                World respawn = worlds.respawnWorld();
                backend.diagnostics().accept("recorded world '" + stored.worldName()
                        + "' not found on this backend; falling back to world spawn");
                return respawn == null ? null : respawn.getSpawnLocation();
            }
        } else {
            world = worlds.respawnWorld();
        }
        if (world == null) {
            backend.diagnostics().accept("no placement world resolvable for operation " + op.operationId());
            return null;
        }
        Position p = stored.position();
        return new Location(world, p.x(), p.y(), p.z(), (float) p.yaw(), (float) p.pitch());
    }

    private void scheduleClaimTimeout(UUID playerUuid, UUID operationId) {
        long timeoutMs = backend.config().network().transfer().timeout().toMillis();
        long delayMs = Math.max(1L, timeoutMs * 2);
        backend.schedulers().runAsyncDelayedFor(delayMs, TimeUnit.MILLISECONDS, () -> claimTimeout(playerUuid, operationId));
    }

    /**
     * Backstop for the whole claim->finalize window: if the player never completed the
     * arrival (disconnected during configuration), terminally fail the operation.
     */
    void claimTimeout(UUID playerUuid, UUID operationId) {
        ArrivalCompletion completion = pending.get(playerUuid);
        if (completion == null || !completion.operationId().equals(operationId)) {
            return;   // completed (or been superseded) in time
        }
        TransferOperation op;
        try {
            op = backend.journal().findById(operationId).orElse(null);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("arrival timeout for operation " + operationId
                    + " could not read the journal: " + e.getMessage());
            return;   // leave the row; a future deploy/fix can reconcile it
        }
        if (op == null || op.state().isTerminal()) {
            pending.remove(playerUuid, completion);
            return;
        }
        Instant failedAt = Instant.now(clock);
        TransferOperation failed = op.withState(OperationState.FAILED, failedAt,
                TransferError.TIMEOUT,
                "player did not complete the arrival handshake within 2x the configured transfer timeout");
        if (backend.journal().compareAndSetState(operationId, op.state(), failed)) {
            OperationState phase = op.state();
            backend.schedulers().runGlobal(() -> backend.events().fireFailed(
                    operationId, null, op.sourceDimension(), op.targetDimension(), op.cause(),
                    TransferError.TIMEOUT, phase, false));
        }
        pending.remove(playerUuid, completion);
    }

    // ------------------------------------------------------- join + finalize

    /**
     * Called from {@code PlayerJoinEvent}. Attaches the sync wait; the placement work runs
     * only once the external sync plugin confirms (or honestly reports nothing confirmed)
     * the player's data, or — when the sync services are missing entirely — proceeds in
     * degraded mode with a warning. Does nothing for players without one. Position work
     * happens on the entity's own scheduler; the failure path never touches the entity.
     */
    public void onJoin(Player player, ArrivalWorldLookup worlds) {
        ArrivalCompletion completion = pending.get(player.getUniqueId());
        if (completion == null) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        SyncOperations sync = resolveSyncOperations();
        if (sync == null) {
            // level-D semantics: no gate available — proceed unconfirmed, warn loudly, never crash
            backend.warn().accept("sync: sync services unavailable for player " + playerUuid
                    + " (plugin disabled or boot failed); proceeding WITHOUT sync confirmation for operation "
                    + completion.operationId() + " in degraded mode. Install HuskSync on every backend, "
                    + "or set sync.plugin=reflection with sync.reflection.event-class, to close this gap.");
            TaskHandle handle = backend.schedulers().runOn(player,
                    () -> finishAfterSync(player, completion, worlds,
                    new SyncReport("unavailable", 0L, true)));
            if (handle == null) {
                failAfterSync(player, completion,
                        new SyncTimeoutException(playerUuid, TimeoutReason.PLAYER_OFFLINE));
            }
            return;
        }
        CompletableFuture<SyncReport> future;
        try {
            future = sync.awaitSync(player);
        } catch (RuntimeException | LinkageError error) {
            backend.severe().accept("sync: awaitSync threw for player " + playerUuid + " (op "
                    + completion.operationId() + "): " + error);
            failAfterSync(player, completion,
                    new SyncTimeoutException(playerUuid, TimeoutReason.INTEGRATION_ERROR));
            return;
        }
        future.whenComplete((report, error) -> {
            if (error != null) {
                failAfterSync(player, completion, error);
                return;
            }
            TaskHandle handle = backend.schedulers().runOn(player,
                    () -> finishAfterSync(player, completion, worlds, report));
            if (handle == null) {
                // the entity retired the exact moment sync completed — nothing to move
                failAfterSync(player, completion,
                        new SyncTimeoutException(playerUuid, TimeoutReason.PLAYER_OFFLINE));
            }
        });
    }

    private @Nullable SyncOperations resolveSyncOperations() {
        try {
            return syncLookup.lookup();
        } catch (RuntimeException | LinkageError error) {
            backend.warn().accept("sync: the sync services lookup failed (" + error
                    + "); the arrival proceeds in degraded mode");
            return null;
        }
    }

    /**
     * Placement work after a normally-completed sync wait. The {@code degraded} flag on a
     * normal completion never blocks the arrival (per the SyncReport contract) — a
     * degraded report means "nothing about the data was confirmed", not "the player's
     * data is corrupt"; it is logged, never fatal. The journal row has no notes/warn
     * column, so the flag is log-only.
     */
    private void finishAfterSync(Player player, ArrivalCompletion completion,
                                 ArrivalWorldLookup worlds, SyncReport report) {
        if (report.degraded()) {
            backend.diagnostics().accept("sync: player " + player.getUniqueId() + " proceeding unconfirmed "
                    + "(strategy=" + report.strategy() + ", degraded=true, took=" + report.durationMillis()
                    + " ms, op " + completion.operationId() + "); transfer proceeds — sync confirmation unavailable");
        }
        PostClaimArrivalStep current = this.step;
        if (current != null && current.appliesTo(completion.operation())) {
            // the step settles asynchronously; guard against a misbehaving step that
            // completes twice — settling twice would teleport twice
            AtomicBoolean settled = new AtomicBoolean(false);
            try {
                current.run(completion, player, worlds, updated -> {
                    if (settled.compareAndSet(false, true)) {
                        settle(updated != null ? updated : completion, player, worlds);
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                backend.diagnostics().accept("post-claim arrival step failed for operation "
                        + completion.operationId() + " (" + e + "); settling at the journal position");
                if (settled.compareAndSet(false, true)) {
                    settle(completion, player, worlds);
                }
            }
            return;
        }
        settle(completion, player, worlds);
    }

    private void settle(ArrivalCompletion completion, Player player, ArrivalWorldLookup worlds) {
        ArrivalCompletion verified = verifyAndFix(completion, player, worlds);
        backend.schedulers().runOnDelayed(player, () -> finalize(verified, player), COMPLETE_EVENT_DELAY_TICKS);
    }

    /**
     * Terminal failure path for a failed sync wait. The player is NOT moved; the row is
     * terminally failed exactly once (CAS discipline — the party that flips the row owns
     * the dispatch); {@code DimensionTransferCompleteEvent} is never dispatched for this
     * operation. {@code phase} on the failed event is the state the failure was detected
     * in — never a terminal state. The event fires even when the player already left,
     * with {@code playerOnline=false} (the api contract requires the entity reference;
     * a stale reference is acceptable).
     */
    private void failAfterSync(Player player, ArrivalCompletion completion, Throwable rawError) {
        UUID opId = completion.operationId();
        UUID playerUuid = player.getUniqueId();
        Throwable cause = unwrap(rawError);
        boolean playerOnline = safeIsOnline(player) && !isOfflineReason(cause);
        TransferError error = mapSyncError(cause);
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();

        TransferOperation op;
        try {
            op = backend.journal().findById(opId).orElse(null);
        } catch (RuntimeException e) {
            pending.remove(playerUuid, completion);
            backend.severe().accept("sync failed for player " + playerUuid + " (op " + opId + "): " + reason
                    + " — journal unreadable, operation left non-terminal (claim watchdog will reconcile)");
            return;
        }
        if (op == null || op.state().isTerminal()) {
            pending.remove(playerUuid, completion);
            backend.diagnostics().accept("operation " + opId + " already terminal at sync failure; FailedEvent skipped");
            return;
        }
        String message = truncate(reason, 500);
        TransferOperation failed = op.withState(OperationState.FAILED, Instant.now(clock), error, message);
        if (!backend.journal().compareAndSetState(opId, op.state(), failed)) {
            pending.remove(playerUuid, completion);
            backend.diagnostics().accept("operation " + opId + " advanced concurrently at sync failure; FailedEvent skipped");
            return;
        }
        OperationState phase = op.state();
        pending.remove(playerUuid, completion);
        backend.severe().accept("sync failed for player " + playerUuid + " (op " + opId + ", error="
                + error.wireName() + ", phase=" + phase.wireName() + "): " + message);
        boolean online = playerOnline;
        backend.schedulers().runGlobal(() -> backend.events().fireFailed(opId, player,
                op.sourceDimension(), op.targetDimension(), op.cause(), error, phase, online));
    }

    /** Unwraps {@link CompletionException}/{@link ExecutionException} nesting around the real cause. */
    private static Throwable unwrap(Throwable raw) {
        Throwable current = raw;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Error mapping for the failure matrix: sync timeout means {@link TransferError#TIMEOUT};
     * player left mid-wait means {@link TransferError#DISCONNECTED}; a broken integration
     * gate means {@link TransferError#UNAVAILABLE}; anything else is {@link TransferError#UNKNOWN}.
     */
    private static TransferError mapSyncError(Throwable cause) {
        if (cause instanceof SyncTimeoutException timeout) {
            return switch (timeout.reason()) {
                case WAIT_TIMEOUT -> TransferError.TIMEOUT;
                case PLAYER_OFFLINE -> TransferError.DISCONNECTED;
                case INTEGRATION_ERROR -> TransferError.UNAVAILABLE;
            };
        }
        return TransferError.UNKNOWN;
    }

    private static boolean isOfflineReason(Throwable cause) {
        return cause instanceof SyncTimeoutException timeout && timeout.reason() == TimeoutReason.PLAYER_OFFLINE;
    }

    /**
     * Best-effort online probe for the failure paths. Prefers the entity's own status (exact,
     * and truthful when called from the entity's owning region), falls back to a registry
     * lookup when the entity access is refused, and to {@code false} when even that is broken —
     * a reference we cannot probe is not an online reference.
     */
    private static boolean safeIsOnline(Player player) {
        try {
            return player.isOnline();
        } catch (RuntimeException | LinkageError error) {
            try {
                return org.bukkit.Bukkit.getPlayer(player.getUniqueId()) != null;
            } catch (RuntimeException | LinkageError fallback) {
                return false;
            }
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Package-visible for tests: teleport fallback logic without the join event. */
    ArrivalCompletion verifyAndFix(ArrivalCompletion completion, Player player, ArrivalWorldLookup worlds) {
        Location target = completion.arrivalLocation();
        if (target == null) {
            return completion;   // no recorded placement — keep the default join position
        }
        World world = target.getWorld();
        Position pos = positionOf(target);
        if (!validator.isValidFor(world, pos, player.getUniqueId(), completion.operation())) {
            World respawn = worlds.respawnWorld();
            if (respawn != null) {
                Location safe = respawn.getSpawnLocation();
                player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                backend.diagnostics().accept("stored respawn position for operation " + completion.operationId()
                        + " is unusable; fell back to world spawn");
                ArrivalCompletion fallback =
                        new ArrivalCompletion(completion.operationId(), completion.operation(), safe);
                pending.put(player.getUniqueId(), fallback);   // keep finalize's identity check sound
                return fallback;
            }
            backend.diagnostics().accept("validator rejected the stored position for operation "
                    + completion.operationId() + " but no respawn world resolves; leaving the player in place");
            return completion;
        }
        Location here = player.getLocation();
        boolean sameWorld = here.getWorld() != null
                && here.getWorld().getUID().equals(world.getUID());
        boolean sameSpot = sameWorld
                && here.getX() == target.getX()
                && here.getY() == target.getY()
                && here.getZ() == target.getZ()
                && wrapYaw(here.getYaw()) == wrapYaw(target.getYaw())
                && here.getPitch() == target.getPitch();
        if (!sameSpot) {
            player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return completion;
    }

    /** Chunk tickets, journal transitions, Complete event. Runs on the entity's scheduler. */
    ArrivalCompletion finalize(ArrivalCompletion completion, Player player) {
        UUID opId = completion.operationId();
        TransferOperation op;
        try {
            op = backend.journal().findById(opId).orElse(null);
        } catch (RuntimeException e) {
            backend.diagnostics().accept("finalize could not read operation " + opId + ": " + e.getMessage());
            pending.remove(player.getUniqueId(), completion);
            return completion;
        }
        if (op == null || op.state().isTerminal()) {
            backend.diagnostics().accept("operation " + opId + " terminated concurrently; skipping CompleteEvent");
            pending.remove(player.getUniqueId(), completion);
            return completion;
        }

        Location anchor = completion.arrivalLocation() != null ? completion.arrivalLocation() : player.getLocation();
        World anchorWorld = anchor.getWorld();
        if (anchorWorld != null) {
            try {
                backend.chunkTickets().addTicket(anchorWorld,
                        RegionMath.chunkCoordinate((int) Math.floor(anchor.getX())),
                        RegionMath.chunkCoordinate((int) Math.floor(anchor.getZ())));
            } catch (RuntimeException e) {
                // tickets are an optimization — never fail the arrival for them
                backend.diagnostics().accept("chunk ticket for operation " + opId + " failed: " + e.getMessage());
            }
        }

        TransferOperation current = op;
        if (current.state() == OperationState.TARGET_CREATED) {
            TransferOperation sourceRemoved = current.withState(
                    OperationState.SOURCE_REMOVED, Instant.now(clock), null, null);
            if (!backend.journal().compareAndSetState(opId, OperationState.TARGET_CREATED, sourceRemoved)) {
                backend.diagnostics().accept("operation " + opId + " advanced concurrently; CompleteEvent skipped");
                pending.remove(player.getUniqueId(), completion);
                return completion;
            }
            current = sourceRemoved;
        }
        if (current.state() != OperationState.SOURCE_REMOVED) {
            backend.diagnostics().accept("operation " + opId + " in unexpected state " + current.state().wireName()
                    + " at finalize; CompleteEvent skipped");
            pending.remove(player.getUniqueId(), completion);
            return completion;
        }
        TransferOperation committed = current.withState(OperationState.COMMITTED, Instant.now(clock), null, null);
        if (!backend.journal().compareAndSetState(opId, OperationState.SOURCE_REMOVED, committed)) {
            backend.diagnostics().accept("operation " + opId + " advanced concurrently at COMMITTED; CompleteEvent skipped");
            pending.remove(player.getUniqueId(), completion);
            return completion;
        }

        long durationMillis = Math.max(0L, Duration.between(committed.createdAt(), Instant.now(clock)).toMillis());
        backend.events().fireComplete(opId, player, committed.sourceDimension(),
                committed.targetDimension(), committed.cause(), durationMillis);
        pending.remove(player.getUniqueId(), completion);
        return completion;
    }

    private static Position positionOf(Location location) {
        return new Position(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static double wrapYaw(double yaw) {
        double wrapped = yaw % 360.0;
        return wrapped < 0 ? wrapped + 360.0 : wrapped;
    }
}
