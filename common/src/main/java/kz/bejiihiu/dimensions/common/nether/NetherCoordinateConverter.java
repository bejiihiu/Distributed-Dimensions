package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.BlockPos;

/**
 * Coordinate conversion between the overworld and the nether, following the Java Edition portal
 * rules: horizontal coordinates scale by the 8:1 nether-to-overworld ratio, Y is never scaled.
 *
 * <p>Overworld → nether floors the scaled horizontal coordinates with {@link Math#floor(double)}:
 * an overworld X of -29 becomes nether X -4, not -3 (a plain cast to int would truncate toward zero
 * and break negative-coordinate linkage). The border clamp happens on the scaled value <i>before</i>
 * the floor, exactly like the vanilla {@code worldBorder.clamp(x * scale, ...) -> BlockPos.ofFloored(...)}
 * chain, so a far-out entity maps onto the outermost in-border block, not past it.
 *
 * <p>Nether → overworld multiplies by 8.0 and does not floor; fractional overworld coordinates are
 * meaningful for entities. The result is clamped to the destination (overworld) border. Y is passed
 * through untouched in both directions — portals link the same altitude.
 *
 * <p>Rotation (yaw/pitch) is preserved by every conversion; only X/Z scale.
 *
 * <p>Vanilla applies a full floor when materialising the destination block position (the Y too).
 * These {@code Position}-based conversions keep fractional Y on purpose; the {@code ...Block}
 * variants floor everything like {@code BlockPos.ofFloored} does.
 */
public final class NetherCoordinateConverter {

    /** Nether coordinate scale relative to the overworld (1 block = 8 blocks). */
    public static final double NETHER_SCALE = 1.0 / 8.0;

    /** Overworld coordinate scale relative to the nether. */
    public static final double OVERWORLD_SCALE = 8.0;

    private NetherCoordinateConverter() {
    }

    /** Overworld → nether. X/Z floored after border clamp; Y, yaw and pitch preserved. */
    public static Position overworldToNether(Position source, WorldBorderBounds netherBorder) {
        if (source == null || netherBorder == null) {
            throw new NullPointerException("source and nether border must not be null");
        }
        double x = Math.floor(netherBorder.clampX(source.x() * NETHER_SCALE));
        double z = Math.floor(netherBorder.clampZ(source.z() * NETHER_SCALE));
        return new Position(x, source.y(), z, source.yaw(), source.pitch());
    }

    /** Nether → overworld. X/Z scaled by 8 and border-clamped; Y, yaw and pitch preserved. */
    public static Position netherToOverworld(Position source, WorldBorderBounds overworldBorder) {
        if (source == null || overworldBorder == null) {
            throw new NullPointerException("source and overworld border must not be null");
        }
        double x = overworldBorder.clampX(source.x() * OVERWORLD_SCALE);
        double z = overworldBorder.clampZ(source.z() * OVERWORLD_SCALE);
        return new Position(x, source.y(), z, source.yaw(), source.pitch());
    }

    /** Overworld → nether as a floored block position (vanilla {@code ofFloored} destination). */
    public static BlockPos overworldToNetherBlock(Position source, WorldBorderBounds netherBorder) {
        Position p = overworldToNether(source, netherBorder);
        return new BlockPos((int) p.x(), (int) Math.floor(p.y()), (int) p.z());
    }

    /** Nether → overworld as a floored block position (vanilla {@code ofFloored} destination). */
    public static BlockPos netherToOverworldBlock(Position source, WorldBorderBounds overworldBorder) {
        Position p = netherToOverworld(source, overworldBorder);
        return new BlockPos((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
    }
}
