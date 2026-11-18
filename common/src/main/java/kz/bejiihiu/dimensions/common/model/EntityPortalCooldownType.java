package kz.bejiihiu.dimensions.common.model;

/**
 * Portal cooldown category applied after an entity teleports through a portal. Defaults track
 * Java Edition (1.21.2+): common entities 300 ticks, vehicles 10 ticks, projectiles ~2 ticks,
 * entity assemblies carrying a player have no cooldown at all.
 */
public enum EntityPortalCooldownType {
    COMMON("common", 300),
    VEHICLE("vehicle", 10),
    PROJECTILE("projectile", 2),
    PLAYER_ASSEMBLY("player_assembly", 0);

    private final String wireName;
    private final int defaultTicks;

    EntityPortalCooldownType(String wireName, int defaultTicks) {
        this.wireName = wireName;
        this.defaultTicks = defaultTicks;
    }

    public String wireName() {
        return wireName;
    }

    /** Vanilla default cooldown length in ticks for this category. */
    public int defaultTicks() {
        return defaultTicks;
    }

    public static EntityPortalCooldownType fromWireName(String wireName) {
        for (EntityPortalCooldownType value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown cooldown type token: '" + wireName + "'");
    }
}
