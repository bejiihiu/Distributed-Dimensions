package kz.bejiihiu.dimensions.api;

/**
 * What triggered a dimension transfer. Used by the implementation for journaling, metrics and
 * to pick the right vanilla delay rules (e.g. the 80-tick Survival portal delay only applies to
 * {@link #NETHER_PORTAL}).
 *
 * <p>The {@link #wireName() tokens} are stable and may be used in metrics and logs.
 *
 * <p>Note: there is no dedicated {@code END_GATEWAY} cause. An End gateway transition is
 * currently reported as {@link #END_PORTAL}; callers that must distinguish gateways should look
 * at the portal location rather than the cause. A dedicated cause may be introduced in a future
 * minor release.
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

    /**
     * Resolves a wire token back to a constant.
     *
     * @throws IllegalArgumentException if the token is unknown
     */
    public static TransferCause fromWireName(String wireName) {
        for (TransferCause value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown transfer cause token: '" + wireName + "'");
    }
}
