package kz.bejiihiu.dimensions.common.model;

/**
 * Lifecycle of one payload row. {@code CLAIMED} is the single point of ownership
 * transfer; {@code FAILED} is terminal and is never retried in place — the source
 * backend decides what to do with the orphaned entity based on its own failure policy.
 */
public enum EntityPayloadState {
    PENDING("pending"),
    CLAIMED("claimed"),
    FAILED("failed");

    private final String wireName;

    EntityPayloadState(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token written to the database. */
    public String wireName() {
        return wireName;
    }

    public static EntityPayloadState fromWireName(String wireName) {
        for (EntityPayloadState value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown payload state token: '" + wireName + "'");
    }
}
