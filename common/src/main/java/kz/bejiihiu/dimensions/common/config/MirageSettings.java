package kz.bejiihiu.dimensions.common.config;

/**
 * Mirage (void) dimensions local to a backend: they make the foreign dimensions
 * exist as empty worlds so plugins that probe for them keep working.
 *
 * <p>{@code fixedTime} is the gamerule time pinned in mirage worlds (noon by
 * convention). {@code blockEdits} selects the guard behavior for edits.
 */
public record MirageSettings(boolean enabled, MirageNamesSettings names, MirageBlockEdits blockEdits, long fixedTime) {

    public MirageSettings {
        if (names == null || blockEdits == null) {
            throw new IllegalArgumentException("mirage.names and mirage.block-edits must be configured");
        }
        if (fixedTime < 0 || fixedTime > 24000) {
            throw new IllegalArgumentException("mirage.fixed-time must be in [0, 24000], got " + fixedTime);
        }
    }
}
