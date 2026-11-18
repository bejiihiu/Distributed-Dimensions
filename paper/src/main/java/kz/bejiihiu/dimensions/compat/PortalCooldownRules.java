package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.jetbrains.annotations.NotNull;

/**
 * The cooldown window per entity type, mirroring vanilla's portal cooldown:
 * ordinary entities 15 s, minecarts and boats 0.5 s, projectiles 0.1 s — and
 * any entity carrying a player passenger never cools down at all.
 *
 * <p>Expressed in milliseconds because the store is wall-clock based ({@code currentTimeMillis}),
 * not tick based; the tick equivalents (300/10/~2) are documented for readers used to ticks.
 */
public final class PortalCooldownRules {

    /** 15 seconds (300 ticks) — ordinary entities. */
    public static final long ORDINARY_MILLIS = 15_000L;
    /** 0.5 seconds (10 ticks) — minecarts and boats. */
    public static final long VEHICLE_MILLIS = 500L;
    /** 0.1 seconds (~2 ticks) — projectiles. */
    public static final long PROJECTILE_MILLIS = 100L;

    private PortalCooldownRules() {
    }

    /** @return true when the entity carries at least one player passenger (never cools down) */
    public static boolean carriesPlayer(@NotNull Entity entity) {
        return entity.getPassengers().stream().anyMatch(passenger -> passenger instanceof Player);
    }

    /**
     * The cooldown window for this entity, in milliseconds.
     *
     * @return {@code -1} when the entity never cools down (carries a player), otherwise the window
     */
    public static long cooldownMillis(@NotNull Entity entity) {
        if (carriesPlayer(entity)) {
            return -1L;
        }
        if (entity instanceof Projectile) {
            return PROJECTILE_MILLIS;
        }
        if (entity instanceof Minecart || entity instanceof Boat) {
            return VEHICLE_MILLIS;
        }
        return ORDINARY_MILLIS;
    }

    /**
     * Whether the entity is inside its cooldown window right now.
     *
     * @param lastPassMillis the last portal-pass timestamp, or null when never
     */
    public static boolean isCoolingDown(@NotNull Entity entity, @org.jetbrains.annotations.Nullable Long lastPassMillis, long nowMillis) {
        if (lastPassMillis == null) {
            return false;
        }
        long ttl = cooldownMillis(entity);
        if (ttl < 0) {
            return false;
        }
        return nowMillis < lastPassMillis + ttl;
    }
}
