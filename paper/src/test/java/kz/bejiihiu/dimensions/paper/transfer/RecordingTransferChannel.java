package kz.bejiihiu.dimensions.paper.transfer;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records payloads instead of pushing them over the wire. Can be primed to throw on
 * send, simulating a dead proxy connection or an unregistered channel.
 */
public final class RecordingTransferChannel implements TransferChannel {

    public record Sent(Player player, byte[] payload) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failSend;
    private volatile TransferChannelSink sink;

    public void setFailSend(boolean failSend) {
        this.failSend = failSend;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    /** Delivers a payload to the installed sink as if it came from the proxy. */
    public void deliver(Player carrier, byte[] payload) {
        TransferChannelSink current = sink;
        if (current != null) {
            current.onMessage(carrier, payload);
        }
    }

    @Override
    public void install(TransferChannelSink sink) {
        this.sink = sink;
    }

    @Override
    public void send(Player player, byte[] payload) {
        if (failSend) {
            throw new TransferChannelException("test-primed send failure");
        }
        sent.add(new Sent(player, payload));
    }
}



