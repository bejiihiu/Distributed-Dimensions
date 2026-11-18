package kz.bejiihiu.dimensions.velocity.transfer;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Velocity-backed implementation of the {@link TransferPlatform} port. Thin by design:
 * all policy lives in the coordinator; this class only translates proxy calls, swallows
 * transport-level exceptions into booleans, and never blocks.
 */
public final class VelocityTransferPlatform implements TransferPlatform {

    private final ProxyServer proxy;
    private final Logger logger;

    public VelocityTransferPlatform(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Override
    public Optional<ServerConnection> currentConnection(UUID playerUuid) {
        return proxy.getPlayer(playerUuid).flatMap(Player::getCurrentServer);
    }

    @Override
    public Optional<RegisteredServer> findServer(String name) {
        return proxy.getServer(name);
    }

    @Override
    public CompletableFuture<ConnectionRequestBuilder.Result> connectTo(UUID playerUuid, RegisteredServer target) {
        try {
            Optional<Player> player = proxy.getPlayer(playerUuid);
            if (player.isEmpty()) {
                return CompletableFuture.failedFuture(new TransferPlayerGoneException(playerUuid));
            }
            // connect() completes after the login handshake with the backend, not after TCP.
            // It never fires KickedFromServerEvent on its own; outcomes arrive through the future.
            return player.get().createConnectionRequest(target).connect();
        } catch (RuntimeException e) {
            // createConnectionRequest/connect must never throw into the listener
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public boolean sendPluginMessage(ServerConnection source, ChannelIdentifier channel, byte[] data) {
        try {
            return source.sendPluginMessage(channel, data);
        } catch (RuntimeException e) {
            logger.warn("failed to send plugin message on channel {}: {}", channel.getId(), e.toString());
            return false;
        }
    }
}
