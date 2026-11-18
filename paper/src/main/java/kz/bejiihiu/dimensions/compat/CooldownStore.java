package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Storage for portal-pass timestamps (the raw last-pass wall-clock millis plus the
 * operation that stamped it). TTL selection happens in {@link PortalCooldownRules},
 * not in the store — the store does not interpret the data.
 *
 * <p>The production implementation is {@link PdcCooldownStore} (data lives on the entity
 * itself via its persistent data container — portable across backends that share nothing).
 * Tests use {@link InMemoryCooldownStore}.
 */
public interface CooldownStore {

    /** Records a portal pass for the entity at {@code atMillis}. */
    void stamp(@NotNull Entity entity, @NotNull UUID operationId, long atMillis);

    /** @return the last-pass timestamp, or null when the entity never used a portal */
    @Nullable Long lastPassMillis(@NotNull Entity entity);

    /** @return the operation id of the last portal pass, or null when unknown */
    @Nullable UUID lastOperationId(@NotNull Entity entity);
}
