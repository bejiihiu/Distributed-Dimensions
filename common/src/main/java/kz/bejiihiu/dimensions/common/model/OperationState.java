package kz.bejiihiu.dimensions.common.model;

/**
 * Lifecycle of a transfer operation (player or entity), mirroring the journal's state column.
 *
 * <p>Happy path: {@code CREATED -> PREPARING -> TARGET_CREATED -> SOURCE_REMOVED -> COMMITTED}.
 * {@link #FAILED} is reachable from every non-terminal state — a failure leaves the source
 * entity/player untouched whenever we haven't crossed the single point of ownership transfer
 * (ACK from the target). {@link #COMMITTED} and {@link #FAILED} are terminal.
 */
public enum OperationState {
    CREATED("created"),
    PREPARING("preparing"),
    TARGET_CREATED("target_created"),
    SOURCE_REMOVED("source_removed"),
    COMMITTED("committed"),
    FAILED("failed");

    private static final java.util.Map<OperationState, java.util.Set<OperationState>> ALLOWED =
            java.util.Map.of(
                    CREATED, java.util.Set.of(PREPARING, FAILED),
                    PREPARING, java.util.Set.of(TARGET_CREATED, FAILED),
                    TARGET_CREATED, java.util.Set.of(SOURCE_REMOVED, FAILED),
                    SOURCE_REMOVED, java.util.Set.of(COMMITTED, FAILED),
                    COMMITTED, java.util.Set.of(),
                    FAILED, java.util.Set.of());

    private final String wireName;

    OperationState(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token stored in the journal. */
    public String wireName() {
        return wireName;
    }

    public static OperationState fromWireName(String wireName) {
        for (OperationState value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown operation state token: '" + wireName + "'");
    }

    public boolean canTransitionTo(OperationState next) {
        java.util.Objects.requireNonNull(next, "next");
        return ALLOWED.get(this).contains(next);
    }

    /** Validates the transition or throws {@link IllegalOperationStateTransitionException}. */
    public OperationState requireTransitionTo(OperationState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalOperationStateTransitionException(this, next);
        }
        return next;
    }

    public boolean isTerminal() {
        return this == COMMITTED || this == FAILED;
    }
}
