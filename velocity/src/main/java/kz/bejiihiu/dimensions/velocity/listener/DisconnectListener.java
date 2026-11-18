package kz.bejiihiu.dimensions.velocity.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import kz.bejiihiu.dimensions.velocity.transfer.TransferCoordinator;
import org.slf4j.Logger;

/**
 * Players can disconnect while a transfer "connect" is in flight. When they do, every
 * in-flight operation of theirs is marked abandoned: removed from the pending set, future
 * replays of the request id are answered with {@code DISCONNECTED}, and the outcome is
 * recorded as abandoned in the recent ring. Nothing is re-kicked - velocity owns the dead
 * connection - and no work is scheduled for it. The underlying connect future is still
 * awaited internally; its completion is observed and ignored with a debug log.
 */
public final class DisconnectListener {

    private final TransferCoordinator coordinator;
    private final Logger logger;

    public DisconnectListener(TransferCoordinator coordinator, Logger logger) {
        this.coordinator = coordinator;
        this.logger = logger;
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        // the event is fired asynchronously and this handler may run before the transport
        // fully tears down - we only touch our own map, never the player object
        int abandoned = coordinator.abandonPlayer(event.getPlayer().getUniqueId());
        if (abandoned > 0) {
            logger.info("player {} disconnected; {} in-flight transfer(s) marked as abandoned",
                    event.getPlayer().getUniqueId(), abandoned);
        }
    }
}
