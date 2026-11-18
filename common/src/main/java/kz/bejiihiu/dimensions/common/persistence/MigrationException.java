package kz.bejiihiu.dimensions.common.persistence;

/**
 * A schema migration failed to apply. Startup must abort when this is thrown — running on an
 * unexpected schema is how journals lose their anti-duplication guarantees.
 */
public final class MigrationException extends PersistenceException {

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
