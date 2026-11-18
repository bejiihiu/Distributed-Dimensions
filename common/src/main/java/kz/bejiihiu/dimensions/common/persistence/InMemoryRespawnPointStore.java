package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link RespawnPointStore} — the implementation used by tests and by
 * embedded deployments that stay on one JVM. Not durable; backends that need the
 * cross-backend guarantee must open the SQL or Mongo engine instead.
 */
public final class InMemoryRespawnPointStore implements RespawnPointStore {

    private final Map<UUID, RespawnPointRecord> records = new ConcurrentHashMap<>();

    @Override
    public void upsert(RespawnPointRecord point) {
        java.util.Objects.requireNonNull(point, "point");
        records.put(point.playerUuid(), point);
    }

    @Override
    public Optional<RespawnPointRecord> find(UUID playerUuid) {
        java.util.Objects.requireNonNull(playerUuid, "playerUuid");
        return Optional.ofNullable(records.get(playerUuid));
    }

    @Override
    public boolean delete(UUID playerUuid) {
        java.util.Objects.requireNonNull(playerUuid, "playerUuid");
        return records.remove(playerUuid) != null;
    }
}
