package kz.bejiihiu.dimensions.common.protocol;

/**
 * Thrown when an inbound protocol payload cannot be decoded into a message. Always a hard
 * failure for that payload — the caller must not attempt a partial application.
 */
public final class ProtocolDecodeException extends Exception {

    private final DecodeError error;

    public ProtocolDecodeException(DecodeError error, String message) {
        super(message);
        this.error = error;
    }

    public ProtocolDecodeException(DecodeError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public DecodeError error() {
        return error;
    }
}
