package kz.bejiihiu.dimensions.common.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ordered migration registry. {@link #CURRENT_VERSION} is the schema version the
 * bundled defaults carry; the registry must provide exactly one migration per version
 * step below it.
 */
final class ConfigMigrations {

    static final int CURRENT_VERSION = 3;

    static final List<ConfigMigration> ALL = List.of(new Migration0To1(), new Migration1To2(), new Migration2To3());

    private ConfigMigrations() {
    }

    private static Map<Integer, ConfigMigration> byFromVersion() {
        Map<Integer, ConfigMigration> map = new HashMap<>();
        for (ConfigMigration migration : ALL) {
            if (map.put(migration.fromVersion(), migration) != null) {
                throw new IllegalStateException(
                        "duplicate migration registered for config-version " + migration.fromVersion());
            }
        }
        return map;
    }

    /**
     * The exact chain of migrations needed to get from {@code from} to {@link #CURRENT_VERSION}.
     * A missing step is a hard error — the registry must be complete, never silently skipped.
     */
    static List<ConfigMigration> pending(int from) {
        Map<Integer, ConfigMigration> map = byFromVersion();
        int version = from;
        List<ConfigMigration> chain = new ArrayList<>();
        while (version < CURRENT_VERSION) {
            ConfigMigration migration = map.get(version);
            if (migration == null) {
                throw new ConfigurationException(
                        "no migration registered for config-version " + version + " → " + (version + 1)
                                + " — the migration registry is incomplete");
            }
            chain.add(migration);
            version = migration.fromVersion() + 1;
        }
        return List.copyOf(chain);
    }
}
