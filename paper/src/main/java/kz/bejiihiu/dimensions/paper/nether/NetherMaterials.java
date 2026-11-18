package kz.bejiihiu.dimensions.paper.nether;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Material/data-level classification for the creation-plan scan. Fluid emptiness is
 * approximated at the material level: {@code WATER}/{@code LAVA} (and any material whose name
 * contains WATER/LAVA) disqualify a block. Waterlogged replaceable blocks (a slab over water,
 * for example) are classified as fluid-empty — a deliberate approximation documented in the
 * mechanics guide; the portal frame still completes, the pocket simply drains like a portal
 * built into water in vanilla.
 */
final class NetherMaterials {

    private NetherMaterials() {
    }

    /** Vanilla {@code isBlockStateValid} approximation: replaceable and no (known) fluid. */
    static boolean isReplaceableNoFluid(BlockData data, Material type) {
        if (data == null || type == null) {
            return false;
        }
        if (!data.isReplaceable()) {
            return false;
        }
        String name = type.name();
        return !name.contains("WATER") && !name.contains("LAVA");
    }

    /** Vanilla {@code BlockState.isSolid()} approximation via the material. */
    static boolean isSolid(Material type) {
        return type != null && type.isSolid();
    }
}
