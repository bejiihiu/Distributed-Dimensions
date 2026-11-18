package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.TransferOperation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The durable operation journal. Storage failures surface as {@link PersistenceException};
 * a recorded-but-duplicate operation id surfaces as {@link DuplicateOperationException}.
 *
 * <p>The journal is what makes the transfer flow safe against retries. Both back ends key
 * idempotency on {@code operationId}, and {@link #compareAndSetState} is the single
 * compare-and-swap primitive used to advance rows — it guarantees that only one racer
 * observes a given from-state → to-state transition as theirs.
 */
public interface TransferOperationJournal {

    /**
     * Inserts a new row in {@link OperationState#CREATED}.
     *
     * @throws DuplicateOperationException when the id already exists
     */
    void recordCreated(TransferOperation operation) throws DuplicateOperationException;

    /**
     * Overwrites the mutable columns (state, updated_at, error*, position) of an existing row.
     * The row is matched by {@code operation_id}; {@code created_at} never changes.
     *
     * @return true when the row existed
     */
    boolean update(TransferOperation operation);

    /**
     * Atomically moves the row from {@code expectedState} to {@code updated.state()}, writing
     * the rest of {@code updated} verbatim. Returns true when the CAS succeeded (we owned the
     * transition), false when the stored row was not in {@code expectedState} — either another
     * party moved it first or the id does not exist.
     */
    boolean compareAndSetState(UUID operationId, OperationState expectedState, TransferOperation updated);

    Optional<TransferOperation> findById(UUID operationId);

    /** All non-terminal rows for a player, oldest first. */
    List<TransferOperation> findActiveForPlayer(UUID playerUuid);

    /** Diagnostics/admin: most recent rows first. {@code limit} is clamped to [1, 1000]. */
    List<TransferOperation> listRecent(int limit);
}
