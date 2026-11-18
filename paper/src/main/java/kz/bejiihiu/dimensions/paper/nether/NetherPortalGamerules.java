package kz.bejiihiu.dimensions.paper.nether;

/**
 * The {@code playersNetherPortalDefaultDelay} / {@code playersNetherPortalCreativeDelay}
 * gamerules of the source world, read per portal completion. Both gamerules are vanilla
 * since Java Edition 1.20.3 (23w42a); on Paper they surface as
 * {@code GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY} and
 * {@code GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY} ({@code GameRule<Integer>}).
 *
 * <p>Vanilla honours these gamerules itself while the player stands in the portal — this
 * plugin does <b>not</b> run its own dwell timer. The reader exists so the delay values
 * can be reported in diagnostics and so the defaults documented in the mechanics guide
 * are derived from the same constants the runtime uses. It is also the seam the unit
 * tests pin the parsing behaviour of.
 */
public interface NetherPortalGamerules {

    /** Gamerule key as stored in the world (vanilla name), for diagnostics only. */
    String DEFAULT_DELAY_KEY = "playersNetherPortalDefaultDelay";

    /** Gamerule key as stored in the world (vanilla name), for diagnostics only. */
    String CREATIVE_DELAY_KEY = "playersNetherPortalCreativeDelay";

    /** Vanilla default for survival/adventure players (1.20.3+). */
    int VANILLA_DEFAULT_DELAY = 80;

    /** Vanilla default for creative players (1.20.3+). */
    int VANILLA_CREATIVE_DELAY = 1;

    /** Delay for non-creative players, in ticks. Never negative. */
    int playersNetherPortalDefaultDelay();

    /** Delay for creative players, in ticks. Never negative. */
    int playersNetherPortalCreativeDelay();

    /**
     * Sanitises a raw gamerule value. Negative values are not meaningful delays — they map
     * to the fallback; {@code null} (missing gamerule) maps to the fallback as well; zero is
     * a legitimate instant-transition setting.
     */
    static int sanitize(Integer raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw < 0) {
            return fallback;
        }
        return raw;
    }

    /** The pure fallback used when the world cannot be read or the gamerules are absent. */
    static NetherPortalGamerules vanillaDefaults() {
        return new NetherPortalGamerules() {
            @Override
            public int playersNetherPortalDefaultDelay() {
                return VANILLA_DEFAULT_DELAY;
            }

            @Override
            public int playersNetherPortalCreativeDelay() {
                return VANILLA_CREATIVE_DELAY;
            }
        };
    }
}
