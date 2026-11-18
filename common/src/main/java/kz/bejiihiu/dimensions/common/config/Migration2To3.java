package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * v2 → v3. Adds {@code network.source-server}, the backend's own registered server
 * name in the proxy. Purely additive with a meaningful bundled default ({@code ""},
 * meaning "unset — transfers fail closed"), so the heal-from-defaults step after
 * migration supplies it — the migration itself rewrites nothing and exists to carry
 * the version bump. Idempotent.
 */
final class Migration2To3 implements ConfigMigration {

    @Override
    public int fromVersion() {
        return 2;
    }

    @Override
    public String id() {
        return "add-source-server-key";
    }

    @Override
    public void migrate(CommentedConfigurationNode root) {
        // additive key — filled in by the heal-from-defaults step, not by a rewrite
    }
}
