package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

/**
 * Opens the persistence layer for the current settings — one factory, two engines.
 *
 * <p>Storage is intentionally dumb: timestamps ({@code createdAt}/{@code updatedAt}) are always
 * supplied by the caller through the {@link kz.bejiihiu.dimensions.common.util.Clock} of their
 * choosing, never read from the system clock inside the store. That keeps the journal fully
 * deterministic under test.
 *
 * <p>SQL: builds a Hikari pool and applies {@link SchemaMigrations#all} before returning —
 * the journal never sees an empty schema, and startup fails loudly (never half-ready) when
 * migration fails.
 *
 * <p>MongoDB: no DDL exists; collections are created implicitly on first write and the
 * secondary indexes are ensured eagerly. {@code mongoUri}'s own pool parameters are
 * overridden by {@link PersistenceSettings#maximumPoolSize()} and
 * {@link PersistenceSettings#minimumIdle()}.
 */
public final class PersistenceFactory {

    /** Used when {@link PersistenceSettings#mongoDatabase()} is null. */
    public static final String DEFAULT_MONGO_DATABASE = "distributed_dimensions";

    private PersistenceFactory() {
    }

    public static DimensionPersistence open(PersistenceSettings settings) {
        java.util.Objects.requireNonNull(settings, "settings");
        String prefix = TableNames.normalize(settings.tablePrefix());
        return switch (settings.type()) {
            case SQL -> openSql(settings, prefix);
            case MONGODB -> openMongo(settings, prefix);
        };
    }

    private static DimensionPersistence openSql(PersistenceSettings settings, String normalizedPrefix) {
        if (settings.jdbcUrl() == null || settings.jdbcUrl().isBlank()) {
            throw new IllegalArgumentException("SQL persistence requires a jdbcUrl");
        }
        var dataSource = HikariDataSourceFactory.create(settings);
        try {
            SchemaMigrationRunner runner =
                    new SchemaMigrationRunner(dataSource, normalizedPrefix, SchemaMigrations.all(normalizedPrefix));
            runner.migrate();
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
        return new DimensionPersistence(
                new SqlTransferOperationJournal(dataSource, normalizedPrefix),
                new SqlPortalIndex(dataSource, normalizedPrefix),
                dataSource::close);
    }

    private static DimensionPersistence openMongo(PersistenceSettings settings, String normalizedPrefix) {
        if (settings.mongoUri() == null || settings.mongoUri().isBlank()) {
            throw new IllegalArgumentException("MongoDB persistence requires a mongoUri");
        }
        String databaseName = settings.mongoDatabase() != null && !settings.mongoDatabase().isBlank()
                ? settings.mongoDatabase()
                : DEFAULT_MONGO_DATABASE;
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(settings.mongoUri()));
        builder.applyToConnectionPoolSettings(pool -> pool
                .maxSize(settings.maximumPoolSize())
                .minSize(settings.minimumIdle()));
        MongoClient client = MongoClients.create(builder.build());
        try {
            MongoDatabase database = client.getDatabase(databaseName);
            String ops = TableNames.table(normalizedPrefix, "transfer_operations");
            String portals = TableNames.table(normalizedPrefix, "portal_index");
            MongoCollection<Document> opsCollection = database.getCollection(ops);
            MongoCollection<Document> portalsCollection = database.getCollection(portals);
            MongoTransferOperationJournal.ensureIndexes(opsCollection);
            MongoPortalIndex.ensureIndexes(portalsCollection);
            return new DimensionPersistence(
                    new MongoTransferOperationJournal(opsCollection),
                    new MongoPortalIndex(portalsCollection),
                    client::close);
        } catch (com.mongodb.MongoException e) {
            client.close();
            throw new PersistenceException("failed to open MongoDB persistence", e);
        }
    }
}
