package kz.bejiihiu.dimensions.common.nether;

/**
 * Vanilla Java Edition portal constants used by this package. Values taken from the decompiled
 * 1.21.x sources and the minecraft.wiki behavior description; they are not guessed.
 */
public final class NetherPortalConstants {

    private NetherPortalConstants() {
    }

    /** Portal search radius in blocks when the destination is the nether (16, square area 32x32). */
    public static final int NETHER_DESTINATION_SEARCH_RADIUS = 16;

    /** Portal search radius in blocks when the destination is the overworld (128, square area 256x256). */
    public static final int OVERWORLD_DESTINATION_SEARCH_RADIUS = 128;

    /** Search radius used by portal creation siting (16 blocks horizontally, any height). */
    public static final int CREATION_SEARCH_RADIUS = 16;

    /** Inner portal width in blocks (2). */
    public static final int PORTAL_INNER_WIDTH = 2;

    /** Inner portal height in blocks (3). */
    public static final int PORTAL_INNER_HEIGHT = 3;

    /** Created portal width including the obsidian frame and corners (4). */
    public static final int PORTAL_FRAME_WIDTH = 4;

    /** Created portal height including the obsidian frame and corners (5). */
    public static final int PORTAL_FRAME_HEIGHT = 5;

    /** Maximum inner portal width (21). */
    public static final int MAX_PORTAL_INNER_WIDTH = 21;

    /** Maximum inner portal height (21). */
    public static final int MAX_PORTAL_INNER_HEIGHT = 21;

    /** Forced placement lower Y bound: below this the portal is unplaceable (vanilla constant 70). */
    public static final int FORCED_PLACEMENT_MIN_Y = 70;

    /** Forced placement is at most this many blocks below the dimension's placeable top. */
    public static final int FORCED_PLACEMENT_TOP_MARGIN = 9;

    /**
     * Search radius for the destination given which dimension the entity is travelling to.
     * Mirrors vanilla: {@code destIsNether -> 16, otherwise 128}.
     */
    public static int searchRadiusForDestination(boolean destinationIsNether) {
        return destinationIsNether ? NETHER_DESTINATION_SEARCH_RADIUS : OVERWORLD_DESTINATION_SEARCH_RADIUS;
    }
}
