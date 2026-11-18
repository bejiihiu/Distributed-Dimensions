package kz.bejiihiu.dimensions.common.model;

/**
 * What triggered a dimension transfer. Used for journal/audit, metrics, and to pick the
 * right vanilla delay rules (e.g. 80-tick Survival portal delay only applies to {@link #NETHER_PORTAL}).
 */
public enum TransferCause {
    NETHER_PORTAL("nether_portal"),
    END_PORTAL("end_portal"),
    RESPAWN("respawn"),
    PROGRAMMATIC("programmatic"),
    MIRAGE_TELEPORT("mirage_teleport");

    private final String wireName;

    TransferCause(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token used on the wire and in the operation journal. */
    public String wireName() {
        return wireName;
    }

    public static TransferCause fromWireName(String wireName) {
        for (TransferCause value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown transfer cause token: '" + wireName + "'");
    }
}
