package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.common.persistence.DuplicateOperationException;
import kz.bejiihiu.dimensions.common.persistence.PersistenceException;
import kz.bejiihiu.dimensions.common.persistence.TransferOperationJournal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-written journal fake mirroring the SQL/Mongo semantics exactly: idempotency is
 * keyed on {@code operationId}, failure transitions are validated the same way, and
 * {@code compareAndSetState} is a real CAS. The point of this fake is honesty — if the
 * orchestration misuses the journal, the fake fails the test the same way the real one
 * fails the operation.
 */
public final class FakeTransferOperationJournal implements TransferOperationJournal {

    private final ConcurrentHashMap<UUID, TransferOperation> rows = new ConcurrentHashMap<>();

    /** Rows in unspecified order — for diagnostics. */
    public List<TransferOperation> rows() {
        return List.copyOf(rows.values());
    }

    @Override
    public void recordCreated(TransferOperation operation) throws DuplicateOperationException {
        TransferOperation previous = rows.putIfAbsent(operation.operationId(), operation);
        if (previous != null) {
            throw new DuplicateOperationException(operation.operationId(),
                    "operation " + operation.operationId() + " is already recorded");
        }
    }

    @Override
    public boolean update(TransferOperation operation) {
        return rows.replace(operation.operationId(), operation) != null;
    }

    @Override
    public boolean compareAndSetState(UUID operationId, OperationState expectedState, TransferOperation updated) {
        TransferOperation current = rows.get(operationId);
        if (current == null || current.state() != expectedState) {
            return false;
        }
        return rows.replace(operationId, current, updated);
    }

    @Override
    public Optional<TransferOperation> findById(UUID operationId) {
        return Optional.ofNullable(rows.get(operationId));
    }

    @Override
    public List<TransferOperation> findActiveForPlayer(UUID playerUuid) {
        return rows.values().stream()
                .filter(r -> r.playerUuid().equals(playerUuid))
                .filter(r -> !r.state().isTerminal())
                .sorted(Comparator.comparing(TransferOperation::createdAt))
                .toList();
    }

    @Override
    public List<TransferOperation> listRecent(int limit) {
        int clamped = Math.max(1, Math.min(1000, limit));
        return rows.values().stream()
                .sorted(Comparator.comparing(TransferOperation::updatedAt).reversed())
                .limit(clamped)
                .toList();
    }
}


