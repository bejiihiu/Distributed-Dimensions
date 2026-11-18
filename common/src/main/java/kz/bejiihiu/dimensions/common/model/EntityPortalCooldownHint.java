package kz.bejiihiu.dimensions.common.model;

/**
 * Cooldown the entity must receive after (re)appearing on the target side of a portal transfer.
 * Carried in the transfer payload so the cooldown travels with the entity — an entity that
 * arrived through our system must be subject to the same post-teleport cooldown checks as a
 * vanilla teleport, or the portal-chunk-loader trick breaks.
 */
public record EntityPortalCooldownHint(EntityPortalCooldownType type, int ticksRemaining) {

    public EntityPortalCooldownHint {
        java.util.Objects.requireNonNull(type, "type");
        if (ticksRemaining < 0) {
            throw new IllegalArgumentException("ticksRemaining must be >= 0, got " + ticksRemaining);
        }
    }

    /** The standard hint for a fresh teleport of this category. */
    public static EntityPortalCooldownHint fresh(EntityPortalCooldownType type) {
        return new EntityPortalCooldownHint(type, type.defaultTicks());
    }
}
