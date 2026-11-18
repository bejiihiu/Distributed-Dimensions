package kz.bejiihiu.dimensions.common.model;

/**
 * Entity kinds that cross the payload store. Mirrors the serializer whitelist in the
 * Paper module — the wire tokens are stable lower-snake values; do not rename one
 * without a data migration.
 */
public enum EntityPayloadKind {
    ITEM("item"),
    ARROW("arrow"),
    BOAT("boat"),
    MINECART("minecart"),
    MINECART_CHEST("minecart_chest"),
    MINECART_FURNACE("minecart_furnace"),
    MINECART_HOPPER("minecart_hopper"),
    MINECART_COMMAND("minecart_command"),
    MINECART_TNT("minecart_tnt"),
    MINECART_SPAWNER("minecart_spawner"),
    HORSE("horse"),
    DONKEY("donkey"),
    MULE("mule"),
    LLAMA("llama"),
    ZOMBIE_HORSE("zombie_horse"),
    SKELETON_HORSE("skeleton_horse"),
    ARMOR_STAND("armour_stand"),
    MOB("mob"),
    UNKNOWN_DEGRADED("unknown_degraded");

    private final String wireName;

    EntityPayloadKind(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token written to the database. */
    public String wireName() {
        return wireName;
    }

    public static EntityPayloadKind fromWireName(String wireName) {
        for (EntityPayloadKind value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown payload kind token: '" + wireName + "'");
    }
}
