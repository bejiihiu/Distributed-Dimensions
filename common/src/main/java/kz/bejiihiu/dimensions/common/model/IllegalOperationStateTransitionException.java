package kz.bejiihiu.dimensions.common.model;

/**
 * Thrown when code attempts a state transition the operation machine forbids
 * (see {@link OperationState}). Always a bug or a corrupted journal row — never an
 * expected control flow.
 */
public final class IllegalOperationStateTransitionException extends IllegalStateException {

    private final OperationState from;
    private final OperationState to;

    public IllegalOperationStateTransitionException(OperationState from, OperationState to) {
        super("illegal operation state transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public OperationState from() {
        return from;
    }

    public OperationState to() {
        return to;
    }
}
