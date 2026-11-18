package kz.bejiihiu.dimensions.common.protocol;

/**
 * The sealed set of messages that may travel between a backend plugin and the Velocity
 * companion over the {@code distributeddimensions:transfer} plugin-messaging channel.
 *
 * <p>Envelope on the wire: {@code {"v":1, "type":"<token>", ...payload}}.
 * <ul>
 *   <li>{@code v} must equal {@link ProtocolVersion#CURRENT} or decoding hard-fails with
 *       {@link DecodeError#UNSUPPORTED_VERSION}.</li>
 *   <li>{@code type} must be a token known to this build or decoding hard-fails with
 *       {@link DecodeError#UNKNOWN_TYPE}.</li>
 *   <li>Unknown fields are <strong>ignored</strong>, both at the top level and inside nested
 *       objects — forward compatibility for additive changes.</li>
 *   <li>Missing required fields and invalid values are <strong>rejected</strong>.</li>
 * </ul>
 *
 * <p>Do not implement this outside the protocol package: the sealed permits list is the
 * registry. Add a new message, add it to the permits clause, bump the codec's type map.
 */
public sealed interface ProtocolMessage
        permits DimensionTransferRequestMessage,
                DimensionTransferResultMessage,
                PlayerRespawnPointQueryMessage,
                PlayerRespawnPointAnswerMessage,
                RespawnAnchorHintMessage,
                EntityTransferMessage,
                EntityTransferAckMessage {

    /** Stable wire type token (snake_case). */
    String type();

    /** Envelope version; always {@link ProtocolVersion#CURRENT}. */
    default int version() {
        return ProtocolVersion.CURRENT;
    }
}
