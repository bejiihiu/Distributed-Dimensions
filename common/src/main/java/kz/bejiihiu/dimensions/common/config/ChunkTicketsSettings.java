package kz.bejiihiu.dimensions.common.config;

/**
 * Destination-chunk ticket policy for entity transfers: keep the portal-destination
 * chunks loaded (entity-ticking level) with a short TTL, refreshed on every transfer.
 * {@code ring} additionally loads a one-chunk ring around the destination.
 */
public record ChunkTicketsSettings(boolean enabled, boolean ring) {
}
