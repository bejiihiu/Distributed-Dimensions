package kz.bejiihiu.dimensions.common.persistence;

/**
 * Thrown when {@link TransferOperationJournal#recordCreated} sees an operation id that is
 * already in the journal — the duplicate-delivery signal the rest of the system is built on.
 */
public final class DuplicateOperationException extends PersistenceException {

    private final java.util.UUID operationId;

    public DuplicateOperationException(java.util.UUID operationId, String message) {
        super(message);
        this.operationId = operationId;
    }

    public DuplicateOperationException(java.util.UUID operationId, String message, Throwable cause) {
        super(message, cause);
        this.operationId = operationId;
    }

    public java.util.UUID operationId() {
        return operationId;
    }
}
