/**
 * Wire protocol between backend plugins and the Velocity companion over a
 * {@code distributeddimensions:transfer} plugin-messaging channel. All messages form a
 * versioned, sealed hierarchy encoded as deterministic JSON; unknown message types are a
 * hard error, unknown fields are ignored (forward compatibility).
 */
package kz.bejiihiu.dimensions.common.protocol;
