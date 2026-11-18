package kz.bejiihiu.dimensions.velocity.transfer;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Narrow port over the Velocity runtime, so the transfer pipeline can be exercised in tests
 * without a running proxy. The production implementation ({@link VelocityTransferPlatform})
 * is a thin binding over the real {@code ProxyServer}; everything interesting - validation
 * order, idempotency gates, timeout mapping, result delivery - lives behind this interface.
 */
public interface TransferPlatform {

    /** The server connection the given player is currently on, if any. */
    Optional<ServerConnection> currentConnection(UUID playerUuid);

    /**
     * True when {@code playerUuid} is online and is connected through this exact
     * {@code ServerConnection} object. Used to prove that a backend request arrived on the
     * connection it claims to belong to.
     */
    default boolean isOnConnection(UUID playerUuid, ServerConnection source) {
        return currentConnection(playerUuid).map(current -> current == source).orElse(false);
    }

    /** Registered server by name, empty when the proxy does not know the name. */
    Optional<RegisteredServer> findServer(String name);

    /**
     * Initiates a switch to {@code target}. Resolves to the connection result once the
     * login completes (or fails); never returns a null future. Completes exceptionally when
     * the target is unreachable, or with {@link TransferPlayerGoneException} when the player
     * is no longer online by the time the connect starts.
     */
    CompletableFuture<com.velocitypowered.api.proxy.ConnectionRequestBuilder.Result> connectTo(
            UUID playerUuid, RegisteredServer target);

    /**
     * Sends plugin-message bytes to a backend over a specific live connection. Returns false
     * when the connection is already unusable (e.g. the player left). Never throws.
     */
    boolean sendPluginMessage(ServerConnection source, ChannelIdentifier channel, byte[] data);
}
