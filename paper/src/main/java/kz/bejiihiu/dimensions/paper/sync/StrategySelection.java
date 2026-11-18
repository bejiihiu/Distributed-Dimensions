package kz.bejiihiu.dimensions.paper.sync;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure strategy selection — no side effects, no Bukkit calls, fully unit-testable.
 * Consumes the results of the environment probes (HuskSync API availability,
 * reflection configuration, known-plugin detection) and produces a decision.
 */
public final class StrategySelection {

    /** What the factory should bind. */
    public enum Strategy {
        HUSKSYNC_API,
        REFLECTION,
        PRESENCE_DELAY,
        DEGRADED_FALLBACK,
        LOUD_FAILURE
    }

    public record Decision(Strategy strategy, String note) {
        public Decision {
            Objects.requireNonNull(strategy, "strategy");
            Objects.requireNonNull(note, "note");
        }
    }

    /**
     * Display names of known sync plugins (matched case-insensitively against
     * installed plugin names). Deliberately small: it only decides the
     * "known presence, no API" tier.
     */
    public static final java.util.List<String> KNOWN_SYNC_PLUGINS = java.util.List.of(
            "HuskSync",
            "MC Data Bridge",
            "MCDataBridge",
            "InvSYNC",
            "InventorySync",
            "DataSynchronization",
            "SyncInv");

    private StrategySelection() {
    }

    /**
     * The first installed plugin whose name matches a known sync plugin
     * (case-insensitive), or {@code null}. Returns the installed name as-is for logs.
     */
    public static String findKnownSyncPlugin(Collection<String> installedPluginNames) {
        if (installedPluginNames == null) {
            return null;
        }
        for (String installed : installedPluginNames) {
            if (installed == null) {
                continue;
            }
            String normalized = installed.trim().toLowerCase(Locale.ROOT);
            for (String known : KNOWN_SYNC_PLUGINS) {
                if (known.toLowerCase(Locale.ROOT).equals(normalized)) {
                    return installed.trim();
                }
            }
        }
        return null;
    }

    /**
     * Resolution order:
     * <ol>
     *   <li>explicit {@code husksync} → HUSKSYNC_API when the API is available, else LOUD_FAILURE</li>
     *   <li>explicit {@code reflection} → REFLECTION when a listener class is configured, else LOUD_FAILURE</li>
     *   <li>explicit {@code delayed} → PRESENCE_DELAY when a known sync plugin is installed, else DEGRADED_FALLBACK</li>
     *   <li>explicit {@code none} → DEGRADED_FALLBACK</li>
     *   <li>{@code auto} → HUSKSYNC_API if available, else REFLECTION if configured,
     *       else PRESENCE_DELAY if a known sync plugin is installed, else DEGRADED_FALLBACK</li>
     * </ol>
     * Unknown {@code sync.plugin} values are treated as {@code none} with a loud note.
     */
    public static Decision select(String syncPluginMode, boolean huskSyncApiAvailable,
            boolean reflectionConfigured, String knownSyncPlugin) {
        String mode = syncPluginMode == null ? "auto" : syncPluginMode.trim().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "husksync":
                return huskSyncApiAvailable
                        ? new Decision(Strategy.HUSKSYNC_API, "sync.plugin=husksync, API available")
                        : new Decision(Strategy.LOUD_FAILURE,
                                "sync.plugin=husksync but the HuskSync API is unavailable (plugin missing or API incompatible)");
            case "reflection":
                return reflectionConfigured
                        ? new Decision(Strategy.REFLECTION, "sync.plugin=reflection, listener class configured")
                        : new Decision(Strategy.LOUD_FAILURE,
                                "sync.plugin=reflection but sync.reflection.event-class is empty");
            case "delayed":
                return knownSyncPlugin != null
                        ? new Decision(Strategy.PRESENCE_DELAY, "sync.plugin=delayed, '" + knownSyncPlugin + "' installed")
                        : new Decision(Strategy.DEGRADED_FALLBACK,
                                "sync.plugin=delayed but no known sync plugin is installed");
            case "none":
                return new Decision(Strategy.DEGRADED_FALLBACK, "sync.plugin=none");
            case "auto":
                if (huskSyncApiAvailable) {
                    return new Decision(Strategy.HUSKSYNC_API, "auto: HuskSync API available");
                }
                if (reflectionConfigured) {
                    return new Decision(Strategy.REFLECTION, "auto: reflection listener configured");
                }
                if (knownSyncPlugin != null) {
                    return new Decision(Strategy.PRESENCE_DELAY, "auto: '" + knownSyncPlugin + "' installed");
                }
                return new Decision(Strategy.DEGRADED_FALLBACK, "auto: no sync plugin detected");
            default:
                return new Decision(Strategy.DEGRADED_FALLBACK,
                        "unknown sync.plugin='" + syncPluginMode + "' — treated as none");
        }
    }
}
