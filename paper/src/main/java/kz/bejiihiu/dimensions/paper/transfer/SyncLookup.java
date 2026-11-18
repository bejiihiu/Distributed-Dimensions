package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.paper.sync.SyncOperations;
import kz.bejiihiu.dimensions.paper.sync.SyncServices;

import org.jetbrains.annotations.Nullable;

/**
 * Resolves the live sync gate at call time. The sync holder is registered late in
 * boot, and the arrival flow needs the lookup — not the instance — so nothing about
 * the arrival flow is bound to boot order.
 *
 * <p>Resolution happens per join, never at construction. A {@code null} return (or a
 * throwing lookup) means the sync services are missing — the arrival proceeds in
 * degraded mode with a warning instead of crashing. Tests inject a controlled
 * lookup for the same reason.
 */
@FunctionalInterface
public interface SyncLookup {

    /** The live sync operations, or {@code null} when they are unavailable. */
    @Nullable
    SyncOperations lookup();

    /** Production wiring: the holder from the ServicesManager, {@code null} when absent or closed. */
    static SyncLookup platform() {
        return () -> {
            try {
                return SyncServices.get().coordinator();
            } catch (RuntimeException | LinkageError error) {
                // disabled, boot failed, or Bukkit statics unavailable (bare JVM tests)
                return null;
            }
        };
    }
}
