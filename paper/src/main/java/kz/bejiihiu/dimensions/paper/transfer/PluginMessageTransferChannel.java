package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Real channel transport: registers the outgoing and incoming plugin channel and sends
 * payloads piggybacked on a player's connection (the only backend -> proxy carrier on
 * this platform; the proxy listens on the same channel and authenticates the sender by
 * connection, not by us).
 */
public final class PluginMessageTransferChannel implements TransferChannel, PluginMessageListener {

    private final JavaPlugin plugin;
    private volatile TransferChannelSink sink;

    public PluginMessageTransferChannel(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void install(TransferChannelSink sink) {
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
        org.bukkit.plugin.messaging.Messenger messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    @Override
    public void send(Player player, byte[] payload) {
        try {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        } catch (RuntimeException e) {
            throw new TransferChannelException("failed to send on channel " + CHANNEL, e);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        TransferChannelSink current = sink;
        if (!CHANNEL.equals(channel) || current == null || message == null) {
            return;
        }
        current.onMessage(player, message);
    }
}
