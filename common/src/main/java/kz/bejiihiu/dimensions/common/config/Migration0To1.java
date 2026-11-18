package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * v0 → v1. The pre-versioning schema is the configuration sketch that shipped in the
 * design docs: it had no {@code config-version} and allowed a stray {@code general.debug}
 * boolean. v1 has neither — the {@code debug.*} section is the single source of truth for
 * verbose logging.
 *
 * <p>Drops {@code general.debug} when present. Everything else v0 needed is supplied by
 * the standard heal-from-defaults step, so there is nothing else to rewrite. Idempotent.
 */
final class Migration0To1 implements ConfigMigration {

    @Override
    public int fromVersion() {
        return 0;
    }

    @Override
    public String id() {
        return "drop-general-debug";
    }

    @Override
    public void migrate(CommentedConfigurationNode root) {
        CommentedConfigurationNode general = root.node("general");
        if (general.isMap() && !general.node("debug").virtual()) {
            general.removeChild("debug");
        }
    }
}
