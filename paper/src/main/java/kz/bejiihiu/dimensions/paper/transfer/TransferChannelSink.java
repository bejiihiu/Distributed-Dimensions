package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.entity.Player;

/**
 * Receives messages from the proxy on the transfer channel. The {@code player} is the
 * player through whose connection the packet arrived — for piggybacked results that is
 * not necessarily the subject of the operation, so never attribute it beyond the channel.
 * Implementations must be non-blocking: hand the bytes to a worker and return.
 */
public interface TransferChannelSink {

    void onMessage(Player carrier, byte[] payload);
}
