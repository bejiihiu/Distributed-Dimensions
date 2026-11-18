package kz.bejiihiu.dimensions.common.config;

/**
 * Cross-server entity transfer switches. {@code players}/{@code mobs}/{@code items}/
 * {@code vehicles} select the categories that may travel; {@code portalsCreate}
 * lets entities create a portal when it is missing (vanilla 1.21+ behavior).
 */
public record CrossServerTransferSettings(
        boolean enabled,
        boolean players,
        boolean mobs,
        boolean items,
        boolean vehicles,
        boolean portalsCreate,
        ChunkTicketsSettings chunkTickets) {

    public CrossServerTransferSettings {
        if (chunkTickets == null) {
            throw new IllegalArgumentException("entities.cross-server-transfer.chunk-tickets must be configured");
        }
    }
}
