package kz.bejiihiu.dimensions.common.config;

/**
 * Integration points for the external player-data sync plugin. {@code waitForSync}
 * blocks the transfer flow until the external plugin acknowledges the sync;
 * {@code plugin} selects the sync plugin by name, or {@code "auto"} to detect it.
 */
public record SyncSettings(boolean enabled, boolean waitForSync, String plugin) {

    public SyncSettings {
        if (plugin == null || plugin.isBlank()) {
            throw new IllegalArgumentException("sync.plugin must not be blank");
        }
        plugin = plugin.trim();
    }
}
