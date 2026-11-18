package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.nether.PlannedPortalBlock;

import org.bukkit.Material;
import org.bukkit.Axis;

/**
 * Pure decision table for the creation plan: which material and which axis each planned block
 * gets. Kept separate from the world-touching code so the table is unit-testable without a
 * live server.
 */
final class NetherPortalBuild {

    /** What a planned block turns into on the ground. */
    record Placement(Material material, Axis axis /* non-null only for portal blocks */) {
        boolean isPortal() {
            return axis != null;
        }
    }

    private NetherPortalBuild() {
    }

    static Placement placementOf(PlannedPortalBlock block, PortalOrientation orientation) {
        return switch (block.type()) {
            case OBSIDIAN -> new Placement(Material.OBSIDIAN, null);
            case AIR -> new Placement(Material.AIR, null);
            case NETHER_PORTAL -> new Placement(Material.NETHER_PORTAL,
                    orientation == PortalOrientation.X_AXIS ? Axis.X : Axis.Z);
        };
    }
}
