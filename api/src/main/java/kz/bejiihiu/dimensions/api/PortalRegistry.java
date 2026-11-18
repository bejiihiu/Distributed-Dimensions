package kz.bejiihiu.dimensions.api;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Read-only view of the portal transition cooldowns tracked by the running implementation.
 *
 * <p>Vanilla keeps a per-entity portal cooldown after a portal transition. The implementation
 * tracks the same cooldown for cross-server transfers so that repeated portal passes behave as
 * they would in a single-server world.
 */
public interface PortalRegistry {

    /**
     * Whether the entity is currently inside its portal cooldown window and therefore should
     * not trigger another portal transition.
     *
     * <p>Semantics mirror vanilla: ordinary entities (mobs, items, …) cool down for
     * <strong>15 seconds (300 ticks)</strong>; minecarts and boats for
     * <strong>0.5 seconds (10 ticks)</strong>; projectiles for <strong>0.1 seconds (~2 ticks)</strong>;
     * an entity carrying a player passenger never cools down at all.
     *
     * <p>Returns {@code false} for entities whose cooldown expired, for entities that have
     * never been observed, and for entities with a player passenger.
     *
     * @param entity the entity to check; must not be {@code null}
     * @return {@code true} if the entity may not use a portal right now
     */
    boolean isPortalCoolingDown(@NotNull Entity entity);
}
