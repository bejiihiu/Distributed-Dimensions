package kz.bejiihiu.dimensions.common.model;

/**
 * What kind of respawn anchor a respawn point refers to. Mirrors vanilla: a bed or a
 * respawn anchor point points at a specific block; {@link #WORLD_SPAWN} is the fallback
 * when no bed/anchor is set.
 */
public enum RespawnKind {
    BED("bed"),
    RESPAWN_ANCHOR("respawn_anchor"),
    WORLD_SPAWN("world_spawn");

    private final String wireName;

    RespawnKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static RespawnKind fromWireName(String wireName) {
        for (RespawnKind value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown respawn kind token: '" + wireName + "'");
    }
}
