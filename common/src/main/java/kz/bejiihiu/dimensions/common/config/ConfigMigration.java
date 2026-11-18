package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * One schema migration, {@code fromVersion → fromVersion + 1}. Applied sequentially by
 * {@link ConfigurationService} when the file's {@code config-version} is behind the
 * current one; the migrated tree is saved back atomically only after every migration
 * in the chain succeeds.
 *
 * <p>Migrations must be idempotent — a failed save means the next boot migrates again.
 * Package-private on purpose: the framework is for the plugin's own schema evolution,
 * not a public extension point, and Configurate types stay out of the public API.
 */
interface ConfigMigration {

    /** The {@code config-version} this migration consumes. */
    int fromVersion();

    /** Machine-readable id used in logs and notes. */
    String id();

    /**
     * Mutates {@code root} in place. Must not read the environment or the filesystem;
     * pure tree surgery only, so broken input can be reported without side effects.
     */
    void migrate(CommentedConfigurationNode root);
}
