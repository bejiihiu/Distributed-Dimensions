package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * v1 → v2. Adds the sync-integration keys: {@code sync.wait-timeout},
 * {@code sync.wait-ticks}, {@code sync.reflection.event-class} and
 * {@code sync.reflection.getter}. All four are purely additive with meaningful
 * bundled defaults, so the heal-from-defaults step after migration supplies them —
 * the migration itself rewrites nothing and exists to carry the version bump.
 * Idempotent.
 */
final class Migration1To2 implements ConfigMigration {

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public String id() {
        return "add-sync-integration-keys";
    }

    @Override
    public void migrate(CommentedConfigurationNode root) {
        // additive keys — filled in by the heal-from-defaults step, not by a rewrite
    }
}
