package kz.bejiihiu.dimensions.common.persistence;

/**
 * Failure of the plugin's state store. Unchecked on purpose: storage errors are never
 * expected control flow, and every caller is a startup or event-path boundary that should
 * fail loudly rather than drizzle SQLExceptions into signatures.
 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message) {
        super(message);
    }

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
