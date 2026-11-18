package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.TransferContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable store for the source portal context of portal-typed transfers. The source backend
 * writes one row next to the journal insert (same operation id); the target backend reads it
 * at claim/arrival time to map the traveller's relative spot onto the destination portal's
 * inner rectangle. Rows are deleted by the target when the arrival completes (or terminally
 * fails), and {@link #deleteOlderThan(Instant)} reaps whatever never reached a terminal path.
 *
 * <p>There is deliberately no upsert: operation ids are v4 UUIDs, a collision is refused by
 * the journal long before it matters here, and a duplicate context row is treated as a
 * persistence failure by the caller (the transfer proceeds without context — the target then
 * falls back to bottom-middle placement, exactly as without the feature).
 *
 * <p>Storage failures surface as {@link PersistenceException}.
 */
public interface TransferContextStore {

    /** Inserts the context row for an operation. Duplicate operation ids are rejected. */
    void put(TransferContext context);

    /** The context recorded for the operation, if any. */
    Optional<TransferContext> find(UUID operationId);

    /** Deletes the context row for an operation (idempotent). */
    int delete(UUID operationId);

    /** Deletes context rows created before {@code cutoff} — stale-row reaping. */
    int deleteOlderThan(Instant cutoff);
}
