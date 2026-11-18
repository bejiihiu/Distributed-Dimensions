package kz.bejiihiu.dimensions.common.config;

/**
 * Compatibility shims for plugins that observe vanilla events.
 *
 * <p>{@code fireSyntheticEvents} — re-dispatch synthetic portal/teleport events on the
 * target server after a cross-server transfer. {@code grantAdvancements} — idempotently
 * credit the criteria of the dimension-entry advancements (enter_the_nether / enter_the_end).
 */
public record ApiCompatSettings(boolean fireSyntheticEvents, boolean grantAdvancements) {
}
