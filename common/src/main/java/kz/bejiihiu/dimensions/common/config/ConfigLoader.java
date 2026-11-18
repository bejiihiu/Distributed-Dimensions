package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The load pipeline: read (or create) the file, run migrations, heal against the
 * bundled defaults, materialize the immutable snapshot. Stateless — the
 * {@link ConfigurationService} owns last-good retention.
 */
final class ConfigLoader {

    /** Outcome of a successful pipeline run. */
    record Loaded(PluginConfig config, ConfigSource source, boolean fileCreated,
                  int migratedFrom, int migratedTo, List<String> warnings, List<String> notes) {
    }

    private ConfigLoader() {
    }

    /**
     * Parses, migrates (saving back atomically when a migration ran), heals and
     * materializes the file at {@code configPath}.
     *
     * @throws ConfigurationException when the file is unreadable, structurally invalid,
     *                                or fails env expansion — caller decides the fallback
     */
    static Loaded load(Environment environment, EnvLookup env) {
        List<String> notes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        java.nio.file.Path path = environment.configPath().toAbsolutePath();

        boolean fileCreated = false;
        if (!FilesSupport.exists(path)) {
            FilesSupport.writeDefaultsFile(environment.defaultsResource(), path);
            fileCreated = true;
            notes.add("wrote default configuration to " + path.getFileName());
        }

        CommentedConfigurationNode defaults = FilesSupport.readDefaults(environment.defaultsResource());
        CommentedConfigurationNode user = FilesSupport.readFile(path);

        int rawVersion = readRawVersion(user);
        List<ConfigMigration> chain = ConfigMigrations.pending(rawVersion);
        int migratedFrom = -1;
        int migratedTo = -1;

        if (!chain.isEmpty()) {
            for (ConfigMigration migration : chain) {
                migration.migrate(user);
                notes.add("migrated config-version " + migration.fromVersion()
                        + " → " + (migration.fromVersion() + 1) + " (" + migration.id() + ")");
            }
            migratedFrom = rawVersion;
            migratedTo = ConfigMigrations.CURRENT_VERSION;
            setConfigVersion(user);
        }

        Nodes.Plan plan = Nodes.analyze(defaults, user);
        CommentedConfigurationNode healed = Nodes.heal(defaults, user, plan); // throws on structural errors
        for (List<String> unknownPath : plan.unknownPaths()) {
            warnings.add(Nodes.dotted(unknownPath));
        }
        setConfigVersion(healed);

        if (!chain.isEmpty()) {
            // persist migrations (with healed content); plain healing never rewrites the file
            FilesSupport.saveFile(path, healed);
            notes.add("saved migrated configuration back to " + path.getFileName());
        }

        PluginConfig config = materialize(healed, env);
        return new Loaded(config, ConfigSource.FILE, fileCreated,
                migratedFrom, migratedTo, List.copyOf(warnings), List.copyOf(notes));
    }

    /** Bundled-defaults snapshot, used as the last resort when no last-good exists. */
    static PluginConfig defaultsSnapshot(Environment environment, EnvLookup env) {
        CommentedConfigurationNode defaults = FilesSupport.readDefaults(environment.defaultsResource());
        return materialize(defaults, env);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static void setConfigVersion(CommentedConfigurationNode root) {
        try {
            root.node("config-version").set(ConfigMigrations.CURRENT_VERSION);
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            // writing an int into a freshly healed tree cannot fail in practice
            throw new ConfigurationException("cannot stamp config-version: " + e.getMessage(), e);
        }
    }

    private static int readRawVersion(CommentedConfigurationNode user) {
        CommentedConfigurationNode node = user.node("config-version");
        if (node.virtual() || node.raw() == null) {
            return 0; // pre-versioning file — migrations bring it up to CURRENT_VERSION
        }
        Object raw = node.raw();
        if (!(raw instanceof Number number)) {
            throw new ConfigurationException("config-version: expected an integer but found " + Nodes.kind(node));
        }
        long value = number.longValue();
        if (value < 0) {
            throw new ConfigurationException("config-version must be >= 0, got " + value);
        }
        if (value > ConfigMigrations.CURRENT_VERSION) {
            throw new ConfigurationException(
                    "config-version " + value + " is newer than this plugin supports ("
                            + ConfigMigrations.CURRENT_VERSION + ") — upgrade the plugin");
        }
        return (int) value;
    }

    private static PluginConfig materialize(CommentedConfigurationNode root, EnvLookup env) {
        try {
            GeneralSettings general = new GeneralSettings(
                    Nodes.string(root, "general", "language"),
                    Nodes.bool(root, "general", "strict-vanilla-mode"));

            NetworkSettings network = new NetworkSettings(
                    new NetworkProxySettings(Nodes.bool(root, "network", "proxy", "enabled")),
                    new TransferSettings(
                            Duration.ofMillis(Nodes.longValue(root, "network", "transfer", "timeout")),
                            Nodes.intValue(root, "network", "transfer", "max-retries")),
                    new DimensionsSettings(
                            dimensionTarget(root, "overworld"),
                            dimensionTarget(root, "nether"),
                            dimensionTarget(root, "end")));

            PortalsSettings portals = new PortalsSettings(
                    new NetherPortalsSettings(
                            Nodes.bool(root, "portals", "nether", "enabled"),
                            Nodes.bool(root, "portals", "nether", "preserve-vanilla-delay"),
                            Nodes.bool(root, "portals", "nether", "preserve-vanilla-search"),
                            Nodes.bool(root, "portals", "nether", "preserve-vanilla-creation"),
                            Nodes.bool(root, "portals", "nether", "preserve-vanilla-orientation")),
                    new EndPortalsSettings(Nodes.bool(root, "portals", "end", "enabled")),
                    new GatewayPortalsSettings(Nodes.bool(root, "portals", "gateway", "enabled")));

            EntitiesSettings entities = new EntitiesSettings(
                    new CrossServerTransferSettings(
                            Nodes.bool(root, "entities", "cross-server-transfer", "enabled"),
                            Nodes.bool(root, "entities", "cross-server-transfer", "players"),
                            Nodes.bool(root, "entities", "cross-server-transfer", "mobs"),
                            Nodes.bool(root, "entities", "cross-server-transfer", "items"),
                            Nodes.bool(root, "entities", "cross-server-transfer", "vehicles"),
                            Nodes.bool(root, "entities", "cross-server-transfer", "portals-create"),
                            new ChunkTicketsSettings(
                                    Nodes.bool(root, "entities", "cross-server-transfer", "chunk-tickets", "enabled"),
                                    Nodes.bool(root, "entities", "cross-server-transfer", "chunk-tickets", "ring"))));

            ApiSettings api = new ApiSettings(
                    Nodes.bool(root, "api", "enabled"),
                    new ApiCompatSettings(
                            Nodes.bool(root, "api", "compat", "fire-synthetic-events"),
                            Nodes.bool(root, "api", "compat", "grant-advancements")));

            SyncSettings sync = new SyncSettings(
                    Nodes.bool(root, "sync", "enabled"),
                    Nodes.bool(root, "sync", "wait-for-sync"),
                    Nodes.string(root, "sync", "plugin"));

            RedisSettings redis = new RedisSettings(Nodes.bool(root, "redis", "enabled"));

            DatabaseSettings database = database(root, env);

            DebugSettings debug = new DebugSettings(
                    Nodes.bool(root, "debug", "portal-search"),
                    Nodes.bool(root, "debug", "coordinate-conversion"),
                    Nodes.bool(root, "debug", "transfers"),
                    Nodes.bool(root, "debug", "entity-transfer"));

            MirageBlockEdits blockEdits;
            try {
                blockEdits = MirageBlockEdits.parse(Nodes.string(root, "mirage", "block-edits"));
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("mirage.block-edits: " + e.getMessage(), e);
            }
            MirageSettings mirage = new MirageSettings(
                    Nodes.bool(root, "mirage", "enabled"),
                    new MirageNamesSettings(
                            Nodes.string(root, "mirage", "names", "overworld"),
                            Nodes.string(root, "mirage", "names", "nether"),
                            Nodes.string(root, "mirage", "names", "end")),
                    blockEdits,
                    Nodes.longValue(root, "mirage", "fixed-time"));

            return new PluginConfig(ConfigMigrations.CURRENT_VERSION, general, network, portals,
                    entities, api, sync, redis, database, debug, mirage);
        } catch (IllegalArgumentException e) {
            // record constructors validate; re-tag as a config failure with everything we know
            throw new ConfigurationException(e.getMessage(), e);
        }
    }

    private static DimensionTarget dimensionTarget(CommentedConfigurationNode root, String branch) {
        return new DimensionTarget(
                Nodes.string(root, "network", "dimensions", branch, "server"),
                Nodes.string(root, "network", "dimensions", branch, "dimension"));
    }

    private static DatabaseSettings database(CommentedConfigurationNode root, EnvLookup env) {
        DatabaseType type;
        try {
            type = DatabaseType.parse(Nodes.string(root, "database", "type"));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("database.type: " + e.getMessage(), e);
        }
        // connection strings may carry ${VAR} references — expanded here, at load time.
        // only the fields relevant to the configured engine are expanded: an unused
        // branch must not fail startup over a broken reference
        String jdbcUrl = type.sql()
                ? expand(blankToNull(Nodes.string(root, "database", "jdbc-url")), "database.jdbc-url", env)
                : blankToNull(Nodes.string(root, "database", "jdbc-url"));
        String mongoUri = !type.sql()
                ? expand(blankToNull(Nodes.string(root, "database", "mongo-uri")), "database.mongo-uri", env)
                : blankToNull(Nodes.string(root, "database", "mongo-uri"));
        String username = type.sql()
                ? expand(blankToNull(Nodes.string(root, "database", "username")), "database.username", env)
                : blankToNull(Nodes.string(root, "database", "username"));
        String password = type.sql()
                ? expand(blankToNull(Nodes.string(root, "database", "password")), "database.password", env)
                : blankToNull(Nodes.string(root, "database", "password"));
        String mongoDatabase = blankToNull(Nodes.string(root, "database", "mongo-database"));

        return new DatabaseSettings(
                type,
                blankToNull(jdbcUrl),
                blankToNull(mongoUri),
                blankToNull(username),
                blankToNull(password),
                mongoDatabase,
                Nodes.intValue(root, "database", "maximum-pool-size"),
                Nodes.intValue(root, "database", "minimum-idle"),
                Duration.ofMillis(Nodes.longValue(root, "database", "connection-timeout")),
                Nodes.string(root, "database", "table-prefix").trim());
    }

    private static String expand(String raw, String keyPath, EnvLookup env) {
        return raw == null ? null : EnvExpander.expand(raw, keyPath, env);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
