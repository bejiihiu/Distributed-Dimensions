package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.PortalRegistry;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The published {@link PortalRegistry} implementation, backed by the entity-local
 * {@link CooldownStore}.
 *
 * <p>Reads only — nothing in this wave writes cooldowns yet (the nether/end waves stamp them
 * when a transfer passes). Reading before any pass exists is the common case and returns
 * {@code false}, exactly like vanilla's fresh entity.
 *
 * <p>The clock is injectable for tests; production uses {@code System::currentTimeMillis}.
 */
public final class PaperPortalRegistry implements PortalRegistry {

    private final CooldownStore store;
    private final LongSupplier clock;

    public PaperPortalRegistry(@NotNull CooldownStore store) {
        this(store, System::currentTimeMillis);
    }

    public PaperPortalRegistry(@NotNull CooldownStore store, @NotNull LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Stamps the entity as having just used a portal (nether/end waves call this). */
    public void stampPass(@NotNull Entity entity, @NotNull java.util.UUID operationId) {
        store.stamp(entity, operationId, clock.getAsLong());
    }

    @Override
    public boolean isPortalCoolingDown(@NotNull Entity entity) {
        if (PortalCooldownRules.carriesPlayer(entity)) {
            return false;
        }
        return PortalCooldownRules.isCoolingDown(entity, store.lastPassMillis(entity), clock.getAsLong());
    }
}
