package kz.bejiihiu.dimensions.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A dimension identifier, mapped to a Minecraft resource key (e.g. {@code minecraft:the_nether}).
 *
 * <p>Three well-known instances are provided for the vanilla dimensions. Arbitrary custom keys
 * are supported so future dimensions (datapack dimensions, test fixtures) don't require API changes.
 *
 * <p>The key must match {@code namespace:path} where both parts are lowercase and follow the
 * Minecraft resource key grammar (letters, digits, {@code _}, {@code .}, {@code -}, and {@code /}
 * in the path). Keys are normalized to lowercase on construction.
 */
public record DimensionId(String key) {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static final DimensionId OVERWORLD = new DimensionId("minecraft:overworld");
    public static final DimensionId NETHER = new DimensionId("minecraft:the_nether");
    public static final DimensionId THE_END = new DimensionId("minecraft:the_end");

    public DimensionId {
        Objects.requireNonNull(key, "key");
        key = key.strip().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("not a valid dimension resource key: '" + key + "'");
        }
    }

    /** Parses a resource key. Accepts any well-formed custom key. */
    public static DimensionId of(String key) {
        return new DimensionId(key);
    }

    /** The part before the colon. */
    public String namespace() {
        return key.substring(0, key.indexOf(':'));
    }

    /** The part after the colon. */
    public String path() {
        return key.substring(key.indexOf(':') + 1);
    }

    public boolean isOverworld() {
        return this.equals(OVERWORLD);
    }

    public boolean isNether() {
        return this.equals(NETHER);
    }

    public boolean isTheEnd() {
        return this.equals(THE_END);
    }

    @Override
    public String toString() {
        return key;
    }
}
