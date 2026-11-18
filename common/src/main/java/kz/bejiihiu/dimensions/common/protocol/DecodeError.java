package kz.bejiihiu.dimensions.common.protocol;

/**
 * Classification of a decode failure, so the proxy can attribute failures in metrics and
 * logs without parsing exception messages.
 */
public enum DecodeError {
    /** Input is not well-formed JSON, or is structured in a way we refuse to descend into. */
    MALFORMED_JSON,
    /** Input exceeds {@link ProtocolCodec#MAX_MESSAGE_BYTES}. */
    OVERSIZE,
    /** A required field is absent. */
    MISSING_FIELD,
    /** A field is present but its value violates the schema (type, range, format, invariant). */
    INVALID_VALUE,
    /** The {@code "v"} field is missing or is not a supported version number. */
    UNSUPPORTED_VERSION,
    /** The {@code "type"} field is absent or names a message type this build doesn't know. */
    UNKNOWN_TYPE
}
