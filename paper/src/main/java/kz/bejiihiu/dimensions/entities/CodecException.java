package kz.bejiihiu.dimensions.entities;

/**
 * The payload graph failed structural validation — the rows are rejected and no entity
 * is spawned (the op ends FAILED). Always a bug or data corruption on one side, never a
 * routine condition.
 */
public final class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
