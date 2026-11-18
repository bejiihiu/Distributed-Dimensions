package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.PluginMessageEncoder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hand-written fake of the velocity {@link ServerConnection} for unit tests. Only the
 * surface the transfer pipeline uses is implemented; the rest is absent by contract of
 * this fixture, not by mocking.
 */
public final class FakeServerConnection implements ServerConnection {

    /** One message observed as sent on this connection. */
    public record Sent(ChannelIdentifier channel, byte[] bytes) {

        public String decodedJson() {
            try {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final ServerInfo serverInfo;
    private final RegisteredServer server;
    public final List<Sent> sent = new ArrayList<>();
    public boolean sendResult = true;

    public FakeServerConnection(String serverName, int port) {
        this.serverInfo = new ServerInfo(serverName, new InetSocketAddress("127.0.0.1", port));
        this.server = new FakeRegisteredServer(serverName, port);
    }

    public ServerInfo serverInfo() {
        return serverInfo;
    }

    /** Decodes the next sent message as JSON for assertions, or null when nothing was sent. */
    public String lastSentJson() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1).decodedJson();
    }

    /** Convenience: decode the last sent message via the production codec. */
    public kz.bejiihiu.dimensions.common.protocol.ProtocolMessage lastSentMessage() {
        if (sent.isEmpty()) {
            return null;
        }
        try {
            return ProtocolCodec.decode(sent.get(sent.size() - 1).bytes());
        } catch (kz.bejiihiu.dimensions.common.protocol.ProtocolDecodeException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public com.velocitypowered.api.proxy.Player getPlayer() {
        return null; // never used by the validation/transfer path under test
    }

    @Override
    public Optional<RegisteredServer> getPreviousServer() {
        return Optional.empty();
    }

    @Override
    public RegisteredServer getServer() {
        return server;
    }

    @Override
    public ServerInfo getServerInfo() {
        return serverInfo;
    }

    @Override
    public boolean isClientLoaded() {
        return false;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, byte[] data) {
        sent.add(new Sent(identifier, data.clone()));
        return sendResult;
    }

    @Override
    public boolean sendPluginMessage(ChannelIdentifier identifier, PluginMessageEncoder dataEncoder) {
        sent.add(new Sent(identifier, new byte[0]));
        return sendResult;
    }

    /** Position helper for building request messages in tests. */
    public static Position position() {
        return new Position(1.5, 64.0, -2.5, 90.0, 0.0);
    }
}
