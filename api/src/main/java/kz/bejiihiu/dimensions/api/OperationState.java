package kz.bejiihiu.dimensions.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Lifecycle of a transfer operation (player or entity), as observed through the public api.
 *
 * <p>Happy path: {@code CREATED -> PREPARING -> TARGET_CREATED -> SOURCE_REMOVED -> COMMITTED}.
 * {@link #FAILED} is reachable from every non-terminal state. {@link #COMMITTED} and
 * {@link #FAILED} are terminal: exactly one of them is reached once per operation.
 *
 * <p>This enum mirrors the journal state machine used internally by the implementation; the
 * {@link #wireName() tokens} are stable and may be used in metrics and logs. Consumers should
 * not depend on the rate at which the intermediate states advance, only on the terminal
 * semantics.
 */
public enum OperationState {
    CREATED("created"),
    PREPARING("preparing"),
    TARGET_CREATED("target_created"),
    SOURCE_REMOVED("source_removed"),
    COMMITTED("committed"),
    FAILED("failed");

    private static final Map<OperationState, Set<OperationState>> ALLOWED = Map.of(
            CREATED, Set.of(PREPARING, FAILED),
            PREPARING, Set.of(TARGET_CREATED, FAILED),
            TARGET_CREATED, Set.of(SOURCE_REMOVED, FAILED),
            SOURCE_REMOVED, Set.of(COMMITTED, FAILED),
            COMMITTED, Set.of(),
            FAILED, Set.of());

    private final String wireName;

    OperationState(String wireName) {
        this.wireName = wireName;
    }

    /** Stable lower-snake token, also used on the wire between backend and proxy. */
    public String wireName() {
        return wireName;
    }

    /**
     * Resolves a wire token back to a constant.
     *
     * @throws IllegalArgumentException if the token is unknown
     */
    public static OperationState fromWireName(String wireName) {
        for (OperationState value : values()) {
            if (value.wireName.equals(wireName)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown operation state token: '" + wireName + "'");
    }

    /** Whether this state may advance to {@code next}. Terminal states accept nothing. */
    public boolean canTransitionTo(OperationState next) {
        Objects.requireNonNull(next, "next");
        return ALLOWED.get(this).contains(next);
    }

    /** Whether this state ends the operation's lifecycle ({@link #COMMITTED} or {@link #FAILED}). */
    public boolean isTerminal() {
        return this == COMMITTED || this == FAILED;
    }
}
