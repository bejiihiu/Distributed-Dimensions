package kz.bejiihiu.dimensions.common.config;

/** Entity-related switches. */
public record EntitiesSettings(CrossServerTransferSettings crossServerTransfer) {

    public EntitiesSettings {
        if (crossServerTransfer == null) {
            throw new IllegalArgumentException("entities.cross-server-transfer must be configured");
        }
    }
}
