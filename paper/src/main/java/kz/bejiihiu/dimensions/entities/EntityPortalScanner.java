package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.nether.PortalInnerRect;
import kz.bejiihiu.dimensions.common.nether.PortalSearch;
import kz.bejiihiu.dimensions.common.nether.PortalSearchResult;
import kz.bejiihiu.dimensions.common.nether.WorldBorderBounds;

import java.util.List;
import java.util.Optional;

/**
 * Portal POI scan feeding the search in {@code common} — the Bukkit-side half of the
 * vanilla algorithm. Only live {@code NETHER_PORTAL} POIs end up in the view; grouping
 * and inner-rectangle math happen inside {@code PortalSearch} (shared with the player-side
 * nether wave).
 */
public final class EntityPortalScanner {

    private EntityPortalScanner() {
    }

    /**
     * Select the vanilla-winning portal for the given destination coordinates (search
     * radius 16 for the nether, 128 for the overworld), or empty when none is in range.
     */
    public static Optional<PortalSearchResult> findPortal(org.bukkit.World world, Position target,
            int searchRadius, WorldBorderBounds border) {
        List<BlockPos> blocks = scanPortalBlocks(world,
                new org.bukkit.Location(world, target.x(), target.y(), target.z()),
                searchRadius);
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        return PortalSearch.select(target, () -> blocks, searchRadius, border);
    }

    /**
     * The inner rectangle of the portal nearest to the given source position — used for
     * exit-math on the source side. The entity is standing inside that portal by
     * construction, so the nearest candidate is the one that matters.
     */
    public static Optional<PortalInnerRect> sourceRect(org.bukkit.World world, org.bukkit.Location near,
            BlockPos eventBlock) {
        if (near == null || eventBlock == null) {
            return Optional.empty();
        }
        List<BlockPos> blocks = scanPortalBlocks(world, near, 24);
        if (blocks.isEmpty()) {
            return Optional.empty();
        }
        return PortalSearch.select(eventBlock, () -> blocks, 24, WorldBorderBounds.UNBOUNDED)
                .map(PortalSearchResult::innerRectangle);
    }

    private static List<BlockPos> scanPortalBlocks(org.bukkit.World world, org.bukkit.Location near, int radius) {
        List<io.papermc.paper.entity.poi.PoiSearchResult> pois = world.locateAllPoiInRange(
                near, poiType -> poiType == io.papermc.paper.entity.poi.PoiTypes.NETHER_PORTAL,
                Math.max(1, radius), io.papermc.paper.entity.poi.PoiType.Occupancy.ANY);
        if (pois == null || pois.isEmpty()) {
            return List.of();
        }
        return pois.stream()
                .map(result -> result.location() == null ? null
                        : new BlockPos(result.location().getBlockX(), result.location().getBlockY(),
                        result.location().getBlockZ()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
