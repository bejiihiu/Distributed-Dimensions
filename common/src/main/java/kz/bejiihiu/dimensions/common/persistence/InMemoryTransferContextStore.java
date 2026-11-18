package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.TransferContext;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link TransferContextStore} — the implementation used by tests and by
 * embedded deployments that stay on one JVM. Not durable; backends that need the
 * cross-backend guarantee must open the SQL or Mongo engine instead.
 */
public final class InMemoryTransferContextStore implements TransferContextStore {

    private final Map<UUID, TransferContext> rows = new ConcurrentHashMap<>();

    @Override
    public void put(TransferContext context) {
        if (rows.putIfAbsent(context.operationId(), context) != null) {
            throw new PersistenceException(
                    "duplicate transfer context for op " + context.operationId(), null);
        }
    }

    @Override
    public Optional<TransferContext> find(UUID operationId) {
        return Optional.ofNullable(rows.get(operationId));
    }

    @Override
    public int delete(UUID operationId) {
        return rows.remove(operationId) == null ? 0 : 1;
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        int before = rows.size();
        rows.values().removeIf(row -> row.createdAt().isBefore(cutoff));
        return before - rows.size();
    }

    /** Test hook: current row count. */
    int size() {
        return rows.size();
    }
}
