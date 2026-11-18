package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB implementation of the context store (sync driver; indexing is ensured on open).
 * Documents are field-per-column with the same lower-snake names as the SQL schema;
 * {@code _id} is the operation id, which makes the insert-idempotency the document key
 * itself.
 */
final class MongoTransferContextStore implements TransferContextStore {

    private final MongoCollection<Document> collection;

    MongoTransferContextStore(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    static void ensureIndexes(MongoCollection<Document> collection) {
        collection.createIndex(
                Indexes.ascending("created_at"),
                new IndexOptions().name("created_at_idx"));
    }

    @Override
    public void put(TransferContext context) {
        Document doc = new Document();
        doc.put("_id", context.operationId().toString());
        doc.put("op_id", context.operationId().toString());
        doc.put("player_uuid", context.playerUuid() == null ? null : context.playerUuid().toString());
        doc.put("source_axis", context.sourceAxis().wireName());
        doc.put("fx", context.fx());
        doc.put("fy", context.fy());
        doc.put("forward", context.forward());
        doc.put("entity_width", context.entityWidth());
        doc.put("entity_height", context.entityHeight());
        doc.put("created_at", new Date(context.createdAt().toEpochMilli()));
        try {
            collection.insertOne(doc);
        } catch (MongoWriteException e) {
            throw new PersistenceException(
                    "duplicate transfer context for op " + context.operationId(), e);
        } catch (MongoException e) {
            throw new PersistenceException(
                    "failed to insert transfer context for op " + context.operationId(), e);
        }
    }

    @Override
    public Optional<TransferContext> find(UUID operationId) {
        try {
            Document doc = collection.find(Filters.eq("_id", operationId.toString())).first();
            return doc == null ? Optional.empty() : Optional.of(mapDocument(doc));
        } catch (MongoException e) {
            throw new PersistenceException("find failed for op " + operationId, e);
        }
    }

    @Override
    public int delete(UUID operationId) {
        try {
            return (int) collection.deleteMany(Filters.eq("_id", operationId.toString())).getDeletedCount();
        } catch (MongoException e) {
            throw new PersistenceException("delete failed for op " + operationId, e);
        }
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        try {
            return (int) collection.deleteMany(
                    Filters.lt("created_at", new Date(cutoff.toEpochMilli()))).getDeletedCount();
        } catch (MongoException e) {
            throw new PersistenceException("deleteOlderThan failed", e);
        }
    }

    private static TransferContext mapDocument(Document doc) {
        String playerUuid = doc.getString("player_uuid");
        Date created = doc.getDate("created_at");
        return new TransferContext(
                UUID.fromString(doc.getString("op_id")),
                playerUuid == null ? null : UUID.fromString(playerUuid),
                PortalOrientation.fromWireName(doc.getString("source_axis")),
                doc.getDouble("fx"),
                doc.getDouble("fy"),
                doc.getDouble("forward"),
                doc.getDouble("entity_width"),
                doc.getDouble("entity_height"),
                created == null ? Instant.EPOCH : created.toInstant());
    }
}
