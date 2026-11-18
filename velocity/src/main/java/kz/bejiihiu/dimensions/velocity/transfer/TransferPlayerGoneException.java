package kz.bejiihiu.dimensions.velocity.transfer;

import java.util.UUID;

/**
 * The player vanished from the proxy between validation and connect (or before the connect
 * could start). Mapped onto {@code TransferError.DISCONNECTED} by the transfer pipeline.
 */
public final class TransferPlayerGoneException extends RuntimeException {

    private final UUID playerUuid;

    public TransferPlayerGoneException(UUID playerUuid) {
        super("player " + playerUuid + " is no longer online");
        this.playerUuid = playerUuid;
    }

    public UUID playerUuid() {
        return playerUuid;
    }
}
