package kz.bejiihiu.dimensions.velocity.transfer;

import com.velocitypowered.api.proxy.ServerConnection;
import kz.bejiihiu.dimensions.common.model.TransferError;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory registry of transfer operations: the pending set (idempotency), the completed
 * cache (outcome replay), and a small ring of recent operations for diagnostics.
 * Everything here is deliberately volatile - the proxy owns no durable state; the backends'
 * shared database is the recovery medium of record, not this class.
 *
 * <p>Idempotency contract, keyed on {@code request_id}:
 * <ul>
 *   <li>first sight of an id - {@link BeginStatus#FRESH}, process and remember;</li>
 *   <li>same id, same player/source/target, operation still running - {@link BeginStatus#IN_FLIGHT},
 *       the caller must not issue a second connection request;</li>
 *   <li>same id, operation finished within the TTL window - {@link BeginStatus#REPLAY} with the
 *       recorded outcome; the caller re-sends that outcome and does not re-connect;</li>
 *   <li>same id with a different player/source/target - {@link BeginStatus#CONFLICT}; rejected
 *       (an id can never name two operations).</li>
 * </ul>
 * The TTL window is {@link #DEFAULT_TTL} and both maps are bounded (see capacities); stale
 * entries are evicted lazily on access and on writes.
 */
public final class TransferOperations {

    public static final int PENDING_CAPACITY = 512;
    public static final int COMPLETED_CAPACITY = 1024;
    public static final int RECENT_CAPACITY = 50;
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    /** What kind of terminal state a recent operation reached. */
    public enum OutcomeKind {
        SUCCESS, FAILED, ABANDONED
    }

    /** Diagnostic record for the recent-operations ring; newest entries are returned first. */
    public record RecentOperation(Instant finishedAt,
                                  UUID requestId,
                                  UUID playerUuid,
                                  String sourceServer,
                                  String targetServer,
                                  OutcomeKind kind,
                                  @Nullable TransferError error,
                                  @Nullable String message,
                                  long durationMillis) {
    }

    /** An operation that is currently being connected/awaited. */
    public record PendingOperation(UUID requestId,
                                   UUID playerUuid,
                                   String sourceServer,
                                   String targetServer,
                                   ServerConnection source,
                                   Instant startedAt,
                                   long startedNanos) {
    }

    /** Completed outcome plus the coordinates needed to detect conflicting replays. */
    public record CompletedOperation(TransferOutcome outcome,
                                     UUID playerUuid,
                                     String sourceServer,
                                     String targetServer,
                                     Instant finishedAt) {
    }

    public enum BeginStatus {
        FRESH, IN_FLIGHT, REPLAY, CONFLICT
    }

    public record BeginResult(BeginStatus status, @Nullable TransferOutcome replay) {

        public static BeginResult fresh() {
            return new BeginResult(BeginStatus.FRESH, null);
        }

        public static BeginResult inFlight() {
            return new BeginResult(BeginStatus.IN_FLIGHT, null);
        }

        public static BeginResult replay(TransferOutcome outcome) {
            return new BeginResult(BeginStatus.REPLAY, outcome);
        }

        public static BeginResult conflict() {
            return new BeginResult(BeginStatus.CONFLICT, null);
        }
    }

    private final TtlCache<UUID, PendingOperation> pending;
    private final TtlCache<UUID, CompletedOperation> completed;
    private final ArrayDeque<RecentOperation> recent = new ArrayDeque<>();
    private final TransferStats stats = new TransferStats();
    private final Clock clock;

    public TransferOperations(Clock clock) {
        this(clock, DEFAULT_TTL.toMillis(), PENDING_CAPACITY, COMPLETED_CAPACITY);
    }

    public TransferOperations(Clock clock, long ttlMillis, int pendingCapacity, int completedCapacity) {
        this.clock = clock;
        this.pending = new TtlCache<>(ttlMillis, pendingCapacity, clock);
        this.completed = new TtlCache<>(ttlMillis, completedCapacity, clock);
    }

    public TransferStats stats() {
        return stats;
    }

    public int pendingSize() {
        return pending.size();
    }

    public int completedSize() {
        return completed.size();
    }

    /**
     * Idempotency gate. Registers a fresh pending operation, or reports what the existing one
     * is. Must be called before any connection attempt is issued.
     */
    public synchronized BeginResult begin(UUID requestId, UUID playerUuid, String sourceServer,
                                          String targetServer, ServerConnection source) {
        PendingOperation existing = pending.get(requestId);
        if (existing != null) {
            if (sameOperation(existing.playerUuid(), existing.sourceServer(), existing.targetServer(),
                    playerUuid, sourceServer, targetServer)) {
                stats.inFlightReplays.incrementAndGet();
                return BeginResult.inFlight();
            }
            stats.conflictingReplays.incrementAndGet();
            return BeginResult.conflict();
        }
        CompletedOperation done = completed.get(requestId);
        if (done != null) {
            if (sameOperation(done.playerUuid(), done.sourceServer(), done.targetServer(),
                    playerUuid, sourceServer, targetServer)) {
                stats.completedReplays.incrementAndGet();
                return BeginResult.replay(done.outcome());
            }
            stats.conflictingReplays.incrementAndGet();
            return BeginResult.conflict();
        }
        pending.put(requestId, new PendingOperation(requestId, playerUuid, sourceServer, targetServer,
                source, Instant.now(clock), System.nanoTime()));
        return BeginResult.fresh();
    }

    /**
     * Terminates a pending operation. Returns the operation so the caller can deliver the
     * outcome; empty when the operation was already removed (e.g. abandoned after a player
     * disconnect, or rolled back by the caller).
     */
    public synchronized Optional<PendingOperation> complete(UUID requestId, TransferOutcome outcome) {
        PendingOperation op = pending.remove(requestId);
        if (op == null) {
            return Optional.empty();
        }
        finished(op, outcome);
        return Optional.of(op);
    }

    /**
     * Marks every in-flight operation of the given player abandoned: removes them from the
     * pending set, records {@code DISCONNECTED} outcomes for future replays, and notes the
     * abandonment in the recent ring. Returns the number of operations abandoned.
     */
    public synchronized int abandonPlayer(UUID playerUuid) {
        List<PendingOperation> drained = pending.drainMatchingValues(op -> op.playerUuid().equals(playerUuid));
        for (PendingOperation op : drained) {
            TransferOutcome outcome = TransferOutcome.failed(TransferError.DISCONNECTED,
                    "player disconnected during the transfer");
            completed.put(op.requestId(), new CompletedOperation(outcome, op.playerUuid(),
                    op.sourceServer(), op.targetServer(), Instant.now(clock)));
            pushRecent(op, OutcomeKind.ABANDONED, outcome, durationMillis(op));
            stats.abandonedOperations.incrementAndGet();
        }
        return drained.size();
    }

    /** Newest first; at most {@code limit} entries, never more than the ring holds. */
    public synchronized List<RecentOperation> recent(int limit) {
        int n = Math.max(0, Math.min(limit, recent.size()));
        if (n == 0) {
            return List.of();
        }
        List<RecentOperation> out = new ArrayList<>(n);
        var it = recent.iterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        return Collections.unmodifiableList(out);
    }

    /** For the status command: live pending operations, oldest first. */
    public List<PendingOperation> pendingSnapshot() {
        return pending.valuesSnapshot();
    }

    private void finished(PendingOperation op, TransferOutcome outcome) {
        Instant now = Instant.now(clock);
        completed.put(op.requestId(), new CompletedOperation(outcome, op.playerUuid(),
                op.sourceServer(), op.targetServer(), now));
        pushRecent(op, outcome.ok() ? OutcomeKind.SUCCESS : OutcomeKind.FAILED, outcome, durationMillis(op));
    }

    private void pushRecent(PendingOperation op, OutcomeKind kind, TransferOutcome outcome, long durationMillis) {
        if (recent.size() >= RECENT_CAPACITY) {
            recent.pollLast();
        }
        recent.addFirst(new RecentOperation(Instant.now(clock), op.requestId(), op.playerUuid(),
                op.sourceServer(), op.targetServer(), kind, outcome.error(), outcome.message(), durationMillis));
    }

    private long durationMillis(PendingOperation op) {
        return (System.nanoTime() - op.startedNanos()) / 1_000_000L;
    }

    private static boolean sameOperation(UUID aPlayer, String aSource, String aTarget,
                                         UUID bPlayer, String bSource, String bTarget) {
        return aPlayer.equals(bPlayer) && aSource.equals(bSource) && aTarget.equals(bTarget);
    }
}
