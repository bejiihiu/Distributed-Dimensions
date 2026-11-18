package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Durable store for entity-transfer payloads — the DB-mediated transport between
 * backends. Rows are written by the source backend before the entity is retired;
 * the target backend claims them with an all-or-nothing compare-and-set, spawns the
 * assembly, then deletes its rows. Exactly one backend owns an operation's rows at
 * a time; a lost claim means another party owns the op and the rows are left alone.
 *
 * <p>There is deliberately no history and no retry: {@code FAILED} rows are dead letters
 * kept for diagnostics and reaped later by {@link #deleteFailedOlderThan(Instant)}.
 * Storage failures surface as {@link PersistenceException}.
 */
public interface EntityPayloadStore {

    /**
     * Inserts all rows of one operation. Called once per operation by the source; the
     * whole assembly is written in one go. Duplicate {@code (opId, seq)} rows are rejected
     * (the store's uniqueness rule) — that is what makes a re-sent operation idempotent.
     */
    void insertAll(List<EntityTransferPayload> rows);

    /** All rows of the op, ordered by {@code seq}. Empty when the op does not exist. */
    List<EntityTransferPayload> findByOpId(String opId);

    /** Number of rows of the op in any state (0 when the op does not exist). */
    int countForOp(String opId);

    /**
     * Distinct pending operation ids for this target server, oldest first —
     * what the poll asks for before claiming.
     */
    List<String> findPendingOpIds(String targetServer, int limit);

    /**
     * Distinct pending operation ids for this target server that travel with the given
     * player (player fast-path probe on join), oldest first.
     */
    List<String> findPendingOpIdsForPlayer(UUID playerUuid, String targetServer, int limit);

    /**
     * All-or-nothing claim: flips every pending row of the op to {@code CLAIMED}.
     * Returns the rows (ordered by {@code seq}) when the claim succeeded in full;
     * otherwise returns an empty list and flips exactly the rows this call claimed
     * to {@code FAILED} (an inconsistent op — some rows already {@code FAILED} or owned
     * by someone else — is never left half-claimed). Rows already claimed by another
     * party are never touched.
     */
    List<EntityTransferPayload> claimOp(String opId, String targetServer, Instant now);

    /** Deletes every row of the op. The target calls this after a successful spawn. */
    int deleteForOp(String opId);

    /**
     * Flips every non-terminal row of the op to {@code FAILED}. Used by the source
     * when its in-memory tracking entry expires before any acknowledgement —
     * the source must not conclude "never spawned", but it must stop waiting.
     */
    int failOp(String opId, Instant now);

    /**
     * Target-side stale-claim sweeper: flips {@code CLAIMED} rows untouched since
     * {@code claimedBefore} to {@code FAILED}. Returns the number flipped.
     */
    int expireStaleClaimed(Instant claimedBefore, Instant now);

    /** Dead-letter reaping: deletes {@code FAILED} rows created before {@code cutoff}. */
    int deleteFailedOlderThan(Instant cutoff);
}
