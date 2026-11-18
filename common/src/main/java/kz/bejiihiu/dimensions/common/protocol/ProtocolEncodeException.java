package kz.bejiihiu.dimensions.common.protocol;

/**
 * Thrown when an outbound message cannot be encoded (or exceeds the channel budget).
 * Encode failures indicate a bug in our own code or a pathological message — they are
 * never caused by the network.
 */
public final class ProtocolEncodeException extends Exception {

    public ProtocolEncodeException(String message) {
        super(message);
    }

    public ProtocolEncodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
