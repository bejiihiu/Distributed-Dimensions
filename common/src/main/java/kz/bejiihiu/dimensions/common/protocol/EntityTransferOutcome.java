package kz.bejiihiu.dimensions.common.protocol;

/**
 * Outcome of creating one entity on the target of an entity transfer.
 *
 * <p>{@code ALREADY_EXISTS} is the lost-ACK path: the target sees the operation id again,
 * finds the entity it already created, and reports that instead of creating a duplicate —
 * the source then safely deletes its copy.
 */
public enum EntityTransferOutcome {
    CREATED("created"),
    ALREADY_EXISTS("already_exists"),
    FAILED("failed");

    private final String wireName;

    EntityTransferOutcome(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static EntityTransferOutcome fromWireName(String wireName) {
        for (EntityTransferOutcome value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown entity transfer outcome token: '" + wireName + "'");
    }
}
