package kz.bejiihiu.dimensions.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ${VAR} expansion semantics: success, missing, blank, and branch-specific expansion. */
class EnvExpansionTest {

    @TempDir
    Path dir;

    private Path configPath() {
        return dir.resolve("config.yml");
    }

    private void write(String yaml) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(configPath(), yaml, StandardCharsets.UTF_8);
    }

    private ConfigurationService service(EnvLookup env) {
        return new ConfigurationService(Environment.resolve(dir), env);
    }

    @Test
    void expansionSucceedsForDatabasePassword() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${TEST_PW}\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of(
                "DB_PASSWORD", "unused-by-user-file",
                "TEST_PW", "s3cret-value")));

        PluginConfig config = service.load();

        assertTrue(service.isHealthy());
        assertEquals("s3cret-value", config.database().password());
        assertEquals("s3cret-value", config.database().toPersistenceSettings().password());
    }

    @Test
    void missingVariableIsAClearFailureNamingTheVariable() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${DEFINITELY_NOT_SET}\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of("DB_PASSWORD", "fallback-ok")));

        PluginConfig config = service.load();

        ConfigurationException error = service.health().orElseThrow();
        assertTrue(error.getMessage().contains("DEFINITELY_NOT_SET"));
        assertTrue(error.getMessage().contains("database.password"));
        assertFalse(error.getMessage().contains("fallback-ok"), "no resolved values may leak into errors");
        assertEquals(ConfigSource.DEFAULTS_FALLBACK, service.report().source());
        assertEquals("en", config.general().language(), "fallback must still produce a usable snapshot");
    }

    @Test
    void blankVariableCountsAsUnset() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${BLANK_VAR}\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of(
                "DB_PASSWORD", "fallback-ok",
                "BLANK_VAR", "   ")));

        service.load();

        assertTrue(service.health().orElseThrow().getMessage().contains("BLANK_VAR"));
    }

    @Test
    void jdbcUrlExpandsReferencesIncludingEmbeddedCredentials() throws Exception {
        write("database:\n  type: \"mysql\"\n  jdbc-url: \"jdbc:mysql://db:3306/app?user=${DB_USER}&password=${DB_PASS}\"\n"
                + "  password: \"${DB_PASS}\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of(
                "DB_PASSWORD", "unused",
                "DB_USER", "app",
                "DB_PASS", "p@ss/word")));

        PluginConfig config = service.load();

        assertTrue(service.isHealthy());
        assertEquals("jdbc:mysql://db:3306/app?user=app&password=p@ss/word",
                config.database().jdbcUrl());
        assertEquals("p@ss/word", config.database().password());
    }

    @Test
    void unusedEngineBranchIsNotExpanded() throws Exception {
        // mongodb deployment: the mysql-only jdbc-url branch must not fail over a broken reference
        write("database:\n  type: \"mongodb\"\n  jdbc-url: \"${DEFINITELY_NOT_SET}\"\n"
                + "  mongo-uri: \"mongodb://localhost:27017\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of("DB_PASSWORD", "x")));

        service.load();

        assertTrue(service.isHealthy(), "unused branch must stay literal");

        // ...and the mirror case: sql deployment with a broken reference in mongo-uri is fine too
        write("database:\n  type: \"mysql\"\n  mongo-uri: \"${DEFINITELY_NOT_SET}\"\n  password: \"literal\"\n");
        service = service(EnvLookup.fixed(Map.of("DB_PASSWORD", "x")));
        service.load();
        assertTrue(service.isHealthy());
    }

    @Test
    void mongoUriExpandsForMongodbType() throws Exception {
        write("database:\n  type: \"mongodb\"\n  mongo-uri: \"mongodb://u:${MONGO_PASS}@mongo:27017/\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of(
                "DB_PASSWORD", "unused",
                "MONGO_PASS", "m0ngo-pw")));

        PluginConfig config = service.load();

        assertTrue(service.isHealthy());
        assertEquals("mongodb://u:m0ngo-pw@mongo:27017/", config.database().mongoUri());
    }

    @Test
    void exceptionMessagesNeverContainResolvedValues() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${MISSING_PW}\"\n");
        String secret = "should-not-appear";
        ConfigurationService service = service(EnvLookup.fixed(Map.of(
                "DB_PASSWORD", secret)));

        service.load();

        String message = service.health().orElseThrow().getMessage();
        assertFalse(message.contains(secret), "error must not carry resolved values");
        assertTrue(message.contains("MISSING_PW"));
    }

    @Test
    void totallyBrokenBootThrowsNamingTheMissingVariable() throws Exception {
        write("database:\n  type: \"mysql\"\n  password: \"${MISSING_PW}\"\n");
        ConfigurationService service = service(EnvLookup.fixed(Map.of()));

        ConfigurationException thrown = assertThrows(ConfigurationException.class, service::load);
        assertTrue(thrown.getMessage().contains("MISSING_PW"));
        assertTrue(thrown.getMessage().contains("DB_PASSWORD"));
    }
}
