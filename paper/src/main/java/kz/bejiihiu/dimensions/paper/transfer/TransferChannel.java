package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.entity.Player;

/**
 * The plugin-messaging channel between this backend and the proxy companion.
 * The wire format is owned by the common module ({@code distributeddimensions:transfer},
 * {@code ProtocolCodec}); this seam exists so the orchestration can be exercised with a
 * fake channel instead of a live server connection.
 */
public interface TransferChannel {

    /** The single channel id both sides register. */
    String CHANNEL = "distributeddimensions:transfer";

    /**
     * Registers outgoing and incoming channels and wires the sink. Called exactly once
     * during plugin enable; the sink receives every message that arrives on the channel.
     */
    void install(TransferChannelSink sink);

    /**
     * Sends a pre-encoded payload to the proxy via the player's connection.
     *
     * @throws TransferChannelException when the payload cannot be sent (unregistered
     *         channel, oversize message, dead connection). Callers must treat this as a
     *         transfer failure, never as a silent drop.
     */
    void send(Player player, byte[] payload);

    /** Raised by {@link #send}; carries no secret material. */
    final class TransferChannelException extends RuntimeException {
        public TransferChannelException(String message) {
            super(message);
        }

        public TransferChannelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
