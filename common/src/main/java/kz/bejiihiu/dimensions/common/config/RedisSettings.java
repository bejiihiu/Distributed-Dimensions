package kz.bejiihiu.dimensions.common.config;

/**
 * Redis availability hint. The plugin works without Redis; when enabled,
 * cross-server messaging may use it instead of plugin messages.
 */
public record RedisSettings(boolean enabled) {
}
