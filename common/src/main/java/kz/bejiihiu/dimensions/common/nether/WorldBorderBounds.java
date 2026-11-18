package kz.bejiihiu.dimensions.common.nether;

/**
 * A rectangular world border as four horizontal bounds, in blocks. Y (height) is not part
 * of the border — the world border is a 2D horizontal clamp.
 *
 * <p>Semantics match the vanilla world border: {@code contains} is inclusive on the west/north
 * (low) edges and exclusive on the east/south (high) edges; {@link #clampX(double)} and
 * {@link #clampZ(double)} clamp into {@code [low, high - 1.0]} and are intended to be followed
 * by a floor (or used on already-integral values), mirroring the vanilla
 * {@code WorldBorder.clamp(x, y, z) -> BlockPos.ofFloored(...)} pair: the effective largest
 * allowed coordinate is {@code high - 1.0}.
 *
 * <p>Vanilla default border: the overworld is a 5.999997e7-wide square centred at the origin
 * (bounds ±29_999_985), so the inclusive block range is ±29_999_984. The notes for this project
 * round that to ±29_999_984 for the bounds themselves; the implementation keeps the true vanilla
 * bounds because the interesting property is the [west, east) / clamp-to-[west, east-1.0]
 * consistency, not the exact constant — at runtime the platform passes the configured border in,
 * and these constants exist for tests and documentation.
 */
public record WorldBorderBounds(double west, double north, double east, double south) {

    /** Default overworld bounds, exactly the vanilla default border (5.999997e7 wide). */
    public static final WorldBorderBounds DEFAULT_OVERWORLD =
            new WorldBorderBounds(-29_999_985.0, -29_999_985.0, 29_999_985.0, 29_999_985.0);

    /**
     * Default nether bounds, scaled from the overworld default by the 1:8 portal ratio:
     * {@code 29_999_985 / 8 == 3_749_998.125}. The notes cite {@code ±3_749_998}, which is the
     * same figure obtained by scaling the inclusive max block ({@code 29_999_984 / 8}); bounds
     * here are continuous doubles, not blocks, so the exact constant is not block-exact — the
     * platform passes the real border at runtime. Inclusive block range: [-3_749_998, 3_749_997].
     */
    public static final WorldBorderBounds DEFAULT_NETHER = DEFAULT_OVERWORLD.scaled(1.0 / 8.0);

    /** A border wide enough that every finite coordinate is inside it. */
    public static final WorldBorderBounds UNBOUNDED = unbounded();

    public WorldBorderBounds {
        if (!Double.isFinite(west) || !Double.isFinite(north) || !Double.isFinite(east) || !Double.isFinite(south)) {
            throw new IllegalArgumentException("border bounds must be finite");
        }
        if (west > east || north > south) {
            throw new IllegalArgumentException(
                    "border low edge above high edge: west=" + west + " east=" + east + ", north=" + north + " south=" + south);
        }
    }

    /** Whole-world bounds; useful for tests and for dimensions without a meaningful border. */
    public static WorldBorderBounds unbounded() {
        return new WorldBorderBounds(-Double.MAX_VALUE / 4.0, -Double.MAX_VALUE / 4.0, Double.MAX_VALUE / 4.0, Double.MAX_VALUE / 4.0);
    }

    /** Scales all four bounds by {@code factor}; the centre stays at the origin. */
    public WorldBorderBounds scaled(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("factor must be finite and > 0, got " + factor);
        }
        return new WorldBorderBounds(west * factor, north * factor, east * factor, south * factor);
    }

    /** Vanilla margin-0 {@code contains}: {@code low <= v < high} per horizontal axis. */
    public boolean contains(double x, double z) {
        return x >= west && x < east && z >= north && z < south;
    }

    /** Block-position variant of {@link #contains(double, double)}. */
    public boolean containsBlock(int x, int z) {
        return contains(x, z);
    }

    /** Clamps an X coordinate into {@code [west, east - 1.0]}, matching vanilla border clamp semantics. */
    public double clampX(double x) {
        return clamp(x, west, east - 1.0);
    }

    /** Clamps a Z coordinate into {@code [north, south - 1.0]}, matching vanilla border clamp semantics. */
    public double clampZ(double z) {
        return clamp(z, north, south - 1.0);
    }

    /**
     * Clamps into the border and floors all three components, like the vanilla
     * {@code WorldBorder.clamp(x, y, z)} + {@code BlockPos.ofFloored} pair.
     */
    public long[] clampToBlock(double x, double y, double z) {
        return new long[]{(long) Math.floor(clampX(x)), (long) Math.floor(y), (long) Math.floor(clampZ(z))};
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
