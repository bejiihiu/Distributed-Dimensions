package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import kz.bejiihiu.dimensions.velocity.transfer.TransferPlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Programmable {@link TransferPlatform} for unit tests. Records every connect attempt and
 * every outbound plugin message so assertions can inspect the exact interaction with the
 * proxy runtime.
 */
public final class FakeTransferPlatform implements TransferPlatform {

    /** A connect attempt captured by the fake. */
    public record ConnectAttempt(UUID playerUuid, RegisteredServer target) {
    }

    /** An outbound plugin message captured by the fake. */
    public record Sent(ServerConnection source, ChannelIdentifier channel, byte[] bytes) {
    }

    private final Map<String, RegisteredServer> servers = new HashMap<>();
    private final Map<UUID, ServerConnection> connectionsByPlayer = new HashMap<>();
    public final List<ConnectAttempt> connectAttempts = new ArrayList<>();
    public final List<Sent> sent = new ArrayList<>();
    public boolean sendPluginMessageResult = true;

    /** The future returned by the next connectTo() call; must be set before a transfer is expected. */
    private CompletableFuture<ConnectionRequestBuilder.Result> connectFuture;

    public void registerServer(String name) {
        servers.put(name, new FakeRegisteredServer(name, 25_600 + servers.size()));
    }

    public void setCurrentConnection(UUID playerUuid, ServerConnection connection) {
        connectionsByPlayer.put(playerUuid, connection);
    }

    public void setConnectFuture(CompletableFuture<ConnectionRequestBuilder.Result> future) {
        this.connectFuture = future;
    }

    @Override
    public Optional<ServerConnection> currentConnection(UUID playerUuid) {
        return Optional.ofNullable(connectionsByPlayer.get(playerUuid));
    }

    @Override
    public Optional<RegisteredServer> findServer(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    @Override
    public CompletableFuture<ConnectionRequestBuilder.Result> connectTo(UUID playerUuid, RegisteredServer target) {
        connectAttempts.add(new ConnectAttempt(playerUuid, target));
        if (connectFuture == null) {
            throw new IllegalStateException(
                    "connectTo() called but no connect future was programmed - set it in the test");
        }
        return connectFuture;
    }

    @Override
    public boolean sendPluginMessage(ServerConnection source, ChannelIdentifier channel, byte[] data) {
        sent.add(new Sent(source, channel, data.clone()));
        return sendPluginMessageResult;
    }

    /** Decodes the last outbound plugin message via the production codec. */
    public kz.bejiihiu.dimensions.common.protocol.ProtocolMessage lastSentMessage() {
        if (sent.isEmpty()) {
            return null;
        }
        try {
            return kz.bejiihiu.dimensions.common.protocol.ProtocolCodec.decode(sent.get(sent.size() - 1).bytes());
        } catch (kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException e) {
            throw new IllegalStateException(e);
        }
    }
}
