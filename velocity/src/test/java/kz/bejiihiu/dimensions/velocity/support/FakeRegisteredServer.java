package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.pointer.Pointers;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Hand-written fake of the velocity {@link RegisteredServer} interface for unit tests.
 * Audience methods are all default no-ops in adventure; only the server-specific surface
 * is implemented.
 */
public final class FakeRegisteredServer implements RegisteredServer {

    private final ServerInfo info;

    public FakeRegisteredServer(String name, int port) {
        this.info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", port));
    }

    @Override
    public Collection<Player> getPlayersConnected() {
        return List.of();
    }

    @Override
    public ServerInfo getServerInfo() {
        return info;
    }

    @Override
    public CompletableFuture<ServerPing> ping() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ServerPing> ping(PingOptions pingOptions) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
        return sendPluginMessageResult;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder dataEncoder) {
        return sendPluginMessageResult;
    }

    /** Programmable result of the piggyback path (RegisteredServer.sendPluginMessage). */
    public boolean sendPluginMessageResult = false;

    @Override
    public Pointers pointers() {
        return Pointers.empty();
    }
}
