package kz.bejiihiu.dimensions.common.config;

/**
 * Nether portal handling. The {@code preserveVanilla*} switches keep parts of the
 * vanilla portal experience instead of replacing it wholesale: the entry delay,
 * the portal-search block check, portal block creation in the target world, and
 * axis orientation of created portals.
 */
public record NetherPortalsSettings(
        boolean enabled,
        boolean preserveVanillaDelay,
        boolean preserveVanillaSearch,
        boolean preserveVanillaCreation,
        boolean preserveVanillaOrientation) {
}
