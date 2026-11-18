package kz.bejiihiu.dimensions.common.config;

import kz.bejiihiu.dimensions.common.persistence.PersistenceSettings;
import kz.bejiihiu.dimensions.common.persistence.PersistenceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DatabaseSettings → PersistenceSettings mapping and secret redaction. */
class DatabaseSettingsTest {

    private static DatabaseSettings sql(String jdbcUrl, String username, String password) {
        return new DatabaseSettings(DatabaseType.MYSQL, jdbcUrl, "mongodb://localhost:27017",
                username, password, "distributed_dimensions", 10, 2,
                Duration.ofSeconds(5), "dd_");
    }

    @Test
    void sqlEnginesMapToSqlPersistence() {
        for (DatabaseType type : new DatabaseType[]{DatabaseType.MYSQL, DatabaseType.MARIADB, DatabaseType.POSTGRESQL}) {
            DatabaseSettings settings = new DatabaseSettings(type, "jdbc:" + type.id() + "://h/db",
                    null, "u", "p", null, 8, 3, Duration.ofMillis(2500), "dd_");
            PersistenceSettings mapped = settings.toPersistenceSettings();
            assertEquals(PersistenceType.SQL, mapped.type());
            assertEquals("jdbc:" + type.id() + "://h/db", mapped.jdbcUrl());
            assertEquals("u", mapped.username());
            assertEquals("p", mapped.password());
            assertEquals(8, mapped.maximumPoolSize());
            assertEquals(3, mapped.minimumIdle());
            assertEquals(Duration.ofMillis(2500), mapped.connectionTimeout());
            assertEquals("dd_", mapped.tablePrefix());
            assertNull(mapped.mongoUri());
        }
    }

    @Test
    void mongodbMapsToMongoPersistence() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.MONGODB,
                "jdbc:mysql://ignored/unused", "mongodb://mongo:27017",
                null, null, "dd_test", 12, 4, Duration.ofSeconds(7), "dd_");
        PersistenceSettings mapped = settings.toPersistenceSettings();
        assertEquals(PersistenceType.MONGODB, mapped.type());
        assertEquals("mongodb://mongo:27017", mapped.mongoUri());
        assertEquals("dd_test", mapped.mongoDatabase());
        assertNull(mapped.jdbcUrl());
        assertEquals(12, mapped.maximumPoolSize());
    }

    @Test
    void blankCredentialsCollapseToNull() {
        DatabaseSettings settings = sql("jdbc:mysql://h/db", "", "");
        PersistenceSettings mapped = settings.toPersistenceSettings();
        assertNull(mapped.username());
        assertNull(mapped.password());
    }

    @Test
    void sqlTypeRequiresJdbcUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> sql("", "u", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> sql(null, "u", "p"));
    }

    @Test
    void mongodbTypeRequiresMongoUri() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings(DatabaseType.MONGODB, null, "",
                        null, null, null, 10, 2, Duration.ofSeconds(5), "dd_"));
    }

    @Test
    void poolBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings(DatabaseType.MYSQL, "jdbc:mysql://h/db", null,
                        null, null, null, 0, 0, Duration.ofSeconds(5), "dd_"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings(DatabaseType.MYSQL, "jdbc:mysql://h/db", null,
                        null, null, null, 4, 5, Duration.ofSeconds(5), "dd_"));
    }

    @Test
    void tablePrefixGrammarIsEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings(DatabaseType.MYSQL, "jdbc:mysql://h/db", null,
                        null, null, null, 4, 1, Duration.ofSeconds(5), "9bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings(DatabaseType.MYSQL, "jdbc:mysql://h/db", null,
                        null, null, null, 4, 1, Duration.ofSeconds(5), "way_to_long_prefix_over_32_chars_xxx"));
        // and the happy path passes
        sql("jdbc:mysql://h/db", "u", "p");
    }

    @Test
    void toStringMasksPassword() {
        DatabaseSettings settings = sql("jdbc:mysql://h/db", "app", "super-secret-password");
        String printed = settings.toString();
        assertFalse(printed.contains("super-secret-password"), "password must never be printed");
        assertTrue(printed.contains("****"));
    }

    @Test
    void toStringMasksCredentialsEmbeddedInUrls() {
        DatabaseSettings sqlWithCreds = sql("jdbc:mysql://app:url-secret@db:3306/app", "app", "plain");
        assertFalse(sqlWithCreds.toString().contains("url-secret"));
        assertTrue(sqlWithCreds.toString().contains("****@db:3306"));

        DatabaseSettings mongoWithCreds = new DatabaseSettings(DatabaseType.MONGODB, null,
                "mongodb://app:mongo-secret@mongo:27017/", null, null, null,
                10, 2, Duration.ofSeconds(5), "dd_");
        assertFalse(mongoWithCreds.toString().contains("mongo-secret"));
    }

    @Test
    void toStringMarksUnsetPassword() {
        DatabaseSettings settings = sql("jdbc:mysql://h/db", "app", null);
        assertTrue(settings.toString().contains("<not set>"));
    }

    @Test
    void persistenceSeamReceivesTheSameValues() {
        DatabaseSettings settings = sql("jdbc:mysql://h/db", "app", "pw");
        PersistenceSettings mapped = settings.toPersistenceSettings();
        assertEquals(settings.maximumPoolSize(), mapped.maximumPoolSize());
        assertEquals(settings.connectionTimeout(), mapped.connectionTimeout());
        assertEquals(settings.tablePrefix(), mapped.tablePrefix());
    }
}
