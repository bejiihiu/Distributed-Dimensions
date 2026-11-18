package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link CooldownStore} for tests and ephemeral use. Keyed by entity UUID;
 * entries are upserted on every {@link #stamp}. Not for production use — cooldowns
 * do not survive a restart and grow unboundedly with distinct entities.
 */
public final class InMemoryCooldownStore implements CooldownStore {

    private record Entry(long atMillis, UUID operationId) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public void stamp(@NotNull Entity entity, @NotNull UUID operationId, long atMillis) {
        entries.put(entity.getUniqueId(), new Entry(atMillis, operationId));
    }

    @Override
    public @Nullable Long lastPassMillis(@NotNull Entity entity) {
        Entry entry = entries.get(entity.getUniqueId());
        return entry == null ? null : entry.atMillis();
    }

    @Override
    public @Nullable UUID lastOperationId(@NotNull Entity entity) {
        Entry entry = entries.get(entity.getUniqueId());
        return entry == null ? null : entry.operationId();
    }

    /** Test helper: forget everything. */
    public void clear() {
        entries.clear();
    }
}
