package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.RespawnKind;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB implementation of the respawn-point store (sync driver; indexing is ensured
 * on open). Documents are plain field-per-column — the same lower-snake names as the
 * wire protocol — so an operator poking at the collection sees nothing surprising.
 *
 * <p>Player uniqueness is the document {@code _id} itself ({@code player_uuid});
 * there is deliberately no secondary copy of the key. {@code updated_at} carries the
 * only secondary index, mirroring the SQL schema.
 */
final class MongoRespawnPointStore implements RespawnPointStore {

    private final MongoCollection<Document> collection;

    MongoRespawnPointStore(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /** Creates the secondary index ({@code _id} is already unique). */
    static void ensureIndexes(MongoCollection<Document> collection) {
        collection.createIndex(Indexes.ascending("updated_at"),
                new IndexOptions().name("updated_at_idx"));
    }

    @Override
    public void upsert(RespawnPointRecord point) {
        try {
            collection.updateOne(
                    Filters.eq("_id", point.playerUuid().toString()),
                    Updates.combine(
                            Updates.set("player_uuid", point.playerUuid().toString()),
                            Updates.set("dimension", point.dimension().key()),
                            Updates.set("world", point.world()),
                            Updates.set("x", point.position().x()),
                            Updates.set("y", point.position().y()),
                            Updates.set("z", point.position().z()),
                            Updates.set("yaw", point.position().yaw()),
                            Updates.set("pitch", point.position().pitch()),
                            Updates.set("kind", point.kind().wireName()),
                            Updates.set("updated_at", new Date(point.updatedAt().toEpochMilli()))),
                    new UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("failed to upsert respawn point for player " + point.playerUuid(), e);
        }
    }

    @Override
    public Optional<RespawnPointRecord> find(UUID playerUuid) {
        try {
            Document document = collection.find(Filters.eq("_id", playerUuid.toString())).first();
            return document == null ? Optional.empty() : Optional.of(mapDocument(document));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("find failed for player " + playerUuid, e);
        }
    }

    @Override
    public boolean delete(UUID playerUuid) {
        try {
            return collection.deleteOne(Filters.eq("_id", playerUuid.toString())).getDeletedCount() == 1;
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("delete failed for player " + playerUuid, e);
        }
    }

    private static RespawnPointRecord mapDocument(Document document) {
        Position position = new Position(
                document.getDouble("x"),
                document.getDouble("y"),
                document.getDouble("z"),
                document.getDouble("yaw"),
                document.getDouble("pitch"));
        Date updated = document.getDate("updated_at");
        return new RespawnPointRecord(
                UUID.fromString(document.getString("_id")),
                DimensionId.of(document.getString("dimension")),
                document.getString("world"),
                position,
                RespawnKind.fromWireName(document.getString("kind")),
                updated == null ? Instant.EPOCH : updated.toInstant());
    }
}
