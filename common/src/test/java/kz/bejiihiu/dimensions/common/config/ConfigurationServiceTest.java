package kz.bejiihiu.dimensions.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boot semantics of the service: first start, heal, unknown keys, broken-file fallbacks. */
class ConfigurationServiceTest {

    private static final EnvLookup ENV = EnvLookup.fixed(Map.of("DB_PASSWORD", "test-password"));

    @TempDir
    Path dir;

    private Path configPath() {
        return dir.resolve("config.yml");
    }

    private ConfigurationService newService() {
        return new ConfigurationService(Environment.resolve(dir), ENV);
    }

    private void write(String yaml) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(configPath(), yaml, StandardCharsets.UTF_8);
    }

    @Test
    void freshStartWritesBundledDefaultsByteForByte() throws Exception {
        ConfigurationService service = newService();
        PluginConfig config = service.load();

        assertTrue(Files.isRegularFile(configPath()), "defaults file must be created on first start");
        String written = Files.readString(configPath(), StandardCharsets.UTF_8);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            String bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(bundled, written, "first start must copy the bundled defaults untouched");
        }
        assertEquals(ConfigSource.FILE, service.report().source());
        assertTrue(service.report().fileCreated());
        assertTrue(service.isHealthy());
        assertEquals(ConfigMigrations.CURRENT_VERSION, config.configVersion());
    }

    @Test
    void partialFileHealsMissingKeysFromDefaultsWithoutRewritingFile() throws Exception {
        String partial = "config-version: 3\ngeneral:\n  language: \"ru\"\n";
        write(partial);
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals("ru", config.general().language(), "user value must win");
        assertTrue(config.general().strictVanillaMode(), "missing key must be healed from defaults");
        assertEquals("nether", config.network().dimensions().nether().server());
        assertEquals(partial, Files.readString(configPath(), StandardCharsets.UTF_8),
                "healing a v3 file must not rewrite it");
        assertTrue(service.health().isEmpty());
        assertEquals(List.of(), service.report().warnings());
    }

    @Test
    void unknownKeysAreWarningsNotErrors() throws Exception {
        write("bogus-top: 1\nportals:\n  nether:\n    bogus-nested: true\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertTrue(service.isHealthy(), "unknown keys must not break the config");
        assertTrue(service.report().warnings().contains("bogus-top"));
        assertTrue(service.report().warnings().contains("portals.nether.bogus-nested"));
        assertEquals("en", config.general().language());
    }

    @Test
    void brokenYamlKeepsLastGoodSnapshot() throws Exception {
        write("general:\n  language: \"ru\"\n");
        ConfigurationService service = newService();
        PluginConfig first = service.load();
        assertEquals("ru", first.general().language());

        Files.writeString(configPath(), "this is: [ not, valid yaml\n", StandardCharsets.UTF_8);
        PluginConfig second = service.load();

        assertEquals("ru", second.general().language(), "last-good snapshot must survive a broken file");
        assertEquals(ConfigSource.LAST_GOOD, service.report().source());
        assertTrue(service.health().isPresent(), "broken file must be exposed via health()");
        assertTrue(service.health().get().getMessage().contains("config.yml"));
        assertFalse(service.isHealthy());
    }

    @Test
    void brokenYamlOnFirstBootFallsBackToDefaults() throws Exception {
        write("{{{ definitely not yaml\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals("en", config.general().language(), "bundled defaults are the only baseline on first boot");
        assertEquals(ConfigSource.DEFAULTS_FALLBACK, service.report().source());
        assertTrue(service.health().isPresent());
    }

    @Test
    void structuralErrorAtKnownKeyBreaksTheConfig() throws Exception {
        write("general: \"not-a-section\"\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals("en", config.general().language(), "must fall back to defaults, not a half-built config");
        assertTrue(service.health().isPresent());
        assertTrue(service.health().get().getMessage().contains("general"));
    }

    @Test
    void scalarWhereDefaultsHaveSectionBreaksTheConfig() throws Exception {
        write("debug: true\n");
        ConfigurationService service = newService();

        assertEquals("en", service.load().general().language());
        assertTrue(service.health().isPresent());
        assertTrue(service.health().get().getMessage().contains("debug"));
    }

    @Test
    void mistypedScalarValueBreaksTheConfig() throws Exception {
        write("portals:\n  end:\n    enabled: \"perhaps\"\n");
        ConfigurationService service = newService();

        assertEquals("en", service.load().general().language());
        ConfigurationException error = service.health().orElseThrow();
        assertTrue(error.getMessage().contains("portals.end.enabled"));
    }

    @Test
    void missingDatabaseEnvVarFailsWithVariableNameNotValue() throws Exception {
        // user file references a variable the environment does not carry
        write("database:\n  type: \"mysql\"\n  password: \"${USER_DB_PASSWORD}\"\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        ConfigurationException error = service.health().orElseThrow();
        assertTrue(error.getMessage().contains("USER_DB_PASSWORD"), "error must name the variable: " + error.getMessage());
        assertFalse(error.getMessage().contains("test-password"), "error must never carry resolved values");
        assertEquals(ConfigSource.DEFAULTS_FALLBACK, service.report().source());
        assertEquals("mysql", config.database().type().id());
    }

    @Test
    void totallyBrokenBootThrowsWhenDefaultsCannotResolveEither() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${USER_DB_PASSWORD}\"\n");
        ConfigurationService service = new ConfigurationService(
                Environment.resolve(dir), EnvLookup.fixed(Map.of()));

        ConfigurationException thrown = assertThrows(ConfigurationException.class, service::load);
        assertTrue(thrown.getMessage().contains("USER_DB_PASSWORD"));
        assertTrue(thrown.getMessage().contains("DB_PASSWORD"),
                "message must also explain why the defaults fallback failed: " + thrown.getMessage());
    }

    @Test
    void migrationV0ToV1AppliesBumpsVersionAndSavesBack() throws Exception {
        write("general:\n  language: \"ru\"\n  debug: true\nredis:\n  enabled: true\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals(ConfigMigrations.CURRENT_VERSION, config.configVersion());
        assertEquals("ru", config.general().language());
        assertTrue(config.redis().enabled(), "user values must survive the migration");
        assertEquals(0, service.report().migratedFrom());
        assertEquals(3, service.report().migratedTo());
        assertTrue(service.report().notes().stream().anyMatch(n -> n.contains("config-version 0")));
        assertTrue(service.report().notes().stream().anyMatch(n -> n.contains("config-version 1")));
        assertTrue(service.report().notes().stream().anyMatch(n -> n.contains("config-version 2")));
        String saved = Files.readString(configPath(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("config-version: 3"), "migrated file must be saved back");
        assertFalse(saved.contains("debug: true"), "legacy general.debug must be dropped by the migration");
    }

    @Test
    void migrationV1ToV2AddsSyncIntegrationKeysAndSavesBack() throws Exception {
        // a genuine v1 file: no sync.wait-timeout / wait-ticks / reflection.* keys
        write("config-version: 1\nsync:\n  plugin: \"delayed\"\nredis:\n  enabled: true\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals(3, config.configVersion());
        assertEquals("delayed", config.sync().plugin(), "user values must survive the migration");
        assertTrue(config.redis().enabled(), "user values must survive the migration");
        assertEquals(Duration.ofMillis(5_000), config.sync().waitTimeout(),
                "new key must be healed from defaults");
        assertEquals(20, config.sync().waitTicks());
        assertEquals("", config.sync().reflectionEventClass());
        assertEquals("getPlayer", config.sync().reflectionGetter());
        assertEquals("", config.network().sourceServer(), "v2 file has no source-server — healed to the default");
        assertEquals(1, service.report().migratedFrom());
        assertEquals(3, service.report().migratedTo());
        String saved = Files.readString(configPath(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("config-version: 3"), "migrated file must be saved back");
        assertTrue(saved.contains("wait-timeout"), "healed key must be persisted");
    }

    @Test
    void migrationV2ToV3AddsSourceServerKeyAndSavesBack() throws Exception {
        // a genuine v2 file: no network.source-server key
        write("""
                config-version: 2
                redis:
                  enabled: true
                """);
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals(3, config.configVersion());
        assertEquals("", config.network().sourceServer(), "new key must be healed from defaults");
        assertTrue(config.redis().enabled(), "user values must survive the migration");
        assertEquals(2, service.report().migratedFrom());
        assertEquals(3, service.report().migratedTo());
        String saved = Files.readString(configPath(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("config-version: 3"), "migrated file must be saved back");
        assertTrue(saved.contains("source-server"), "healed key must be persisted");
    }

    @Test
    void migrationV2ToV3KeepsAnExplicitSourceServer() throws Exception {
        write("""
                config-version: 2
                network:
                  source-server: "nether-backend"
                """);
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals(3, config.configVersion());
        assertEquals("nether-backend", config.network().sourceServer(),
                "an explicitly configured source-server must survive the migration untouched");
        String saved = Files.readString(configPath(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("nether-backend"));
    }

    @Test
    void defaultsRoundTripCarriesV3Defaults() {
        PluginConfig config = ConfigLoader.defaultsSnapshot(Environment.resolve(dir), ENV);

        assertEquals(ConfigMigrations.CURRENT_VERSION, config.configVersion());
        assertEquals(3, config.configVersion());
        assertEquals("", config.network().sourceServer(), "bundled default must be the unset sentinel");
    }

    @Test
    void configVersionNewerThanSupportedIsAClearFailure() throws Exception {
        write("config-version: 99\n");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertEquals("en", config.general().language(), "must fall back, not explode mid-boot");
        assertTrue(service.health().orElseThrow().getMessage().contains("99"));
    }

    @Test
    void nonIntegerConfigVersionIsAClearFailure() throws Exception {
        write("config-version: \"one\"\n");
        ConfigurationService service = newService();

        assertEquals("en", service.load().general().language());
        assertTrue(service.health().orElseThrow().getMessage().contains("config-version"));
    }

    @Test
    void healOnlyLoadKeepsUserFileUntouchedEvenWithWarningsAndHealing() throws Exception {
        String partial = "config-version: 3\nbogus:\n  key: 1\ngeneral:\n  language: \"ru\"\n";
        write(partial);

        newService().load();

        assertEquals(partial, Files.readString(configPath(), StandardCharsets.UTF_8));
    }

    @Test
    void diagnosticsConsumerReceivesWarningsAndErrors() throws Exception {
        write("bogus-top: 1\n");
        List<String> lines = new ArrayList<>();
        ConfigurationService service = new ConfigurationService(
                Environment.resolve(dir), ENV, lines::add);

        service.load();

        assertTrue(lines.stream().anyMatch(l -> l.contains("bogus-top")), "warnings must reach the diagnostics sink");
    }

    @Test
    void emptyFileHealsToFullDefaultsAndIsSaved() throws Exception {
        write("");
        ConfigurationService service = newService();

        PluginConfig config = service.load();

        assertTrue(service.isHealthy());
        assertEquals("en", config.general().language());
        String saved = Files.readString(configPath(), StandardCharsets.UTF_8);
        assertTrue(saved.contains("config-version: 3"), "empty file is a v0 file — the migration save brings it up");
        assertTrue(saved.contains("mirage"), "saved file must carry the healed defaults");
    }

    @Test
    void nonWholeNumbersAreRejected() throws Exception {
        write("config-version: 1\nnetwork:\n  transfer:\n    timeout: 10.5\n");
        ConfigurationService service = newService();

        service.load();

        assertTrue(service.health().isPresent(), "a fractional timeout must not be silently truncated");
        assertTrue(service.health().get().getMessage().contains("network.transfer.timeout"));
    }

    @Test
    void configThrowsBeforeLoad() {
        ConfigurationService service = newService();
        assertThrows(IllegalStateException.class, service::config);
    }
}
