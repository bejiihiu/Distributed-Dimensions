package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * v3 → v4. Drops the fiction keys: schema entries that nothing ever branched on,
 * shipped as comments promising behavior that did not exist. The v3 schema parsed
 * them and the docs marked them "under review"; v4 deletes them outright.
 *
 * <p>Removed: {@code general.strict-vanilla-mode}; {@code network.proxy.enabled}
 * (the whole {@code network.proxy} section); {@code network.transfer.max-retries};
 * {@code portals.nether.preserve-vanilla-delay}, {@code -search}, {@code -creation},
 * {@code -orientation}; {@code portals.gateway.enabled} (the whole
 * {@code portals.gateway} section); the whole {@code debug.*} section; the whole
 * {@code redis.*} section. Everything else is untouched, values included.
 * Absent keys are fine — the migration only removes what is present. Idempotent.
 */
final class Migration3To4 implements ConfigMigration {

    @Override
    public int fromVersion() {
        return 3;
    }

    @Override
    public String id() {
        return "drop-fiction-keys";
    }

    @Override
    public void migrate(CommentedConfigurationNode root) {
        removeNode(root, "general", "strict-vanilla-mode");

        removeNode(root, "network", "proxy");
        removeNode(root, "network", "transfer", "max-retries");

        removeNode(root, "portals", "nether", "preserve-vanilla-delay");
        removeNode(root, "portals", "nether", "preserve-vanilla-search");
        removeNode(root, "portals", "nether", "preserve-vanilla-creation");
        removeNode(root, "portals", "nether", "preserve-vanilla-orientation");
        removeNode(root, "portals", "gateway");

        removeNode(root, "debug");
        removeNode(root, "redis");
    }

    /**
     * Removes the child at {@code path} when every level above it exists as a section.
     * A scalar where a section is expected is left alone — the analyze step after
     * migrations reports it with a clear message naming the path.
     */
    private static void removeNode(CommentedConfigurationNode root, String... path) {
        CommentedConfigurationNode parent = root;
        for (int i = 0; i < path.length - 1; i++) {
            CommentedConfigurationNode child = parent.node((Object[]) new String[]{path[i]});
            if (child.virtual() || !child.isMap()) {
                return;
            }
            parent = child;
        }
        parent.removeChild(path[path.length - 1]);
    }
}
