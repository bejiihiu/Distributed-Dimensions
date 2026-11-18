package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.IndexOptions;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB implementation of the journal (sync driver; indexing is ensured on open).
 * Documents are plain field-per-column — the same lower-snake names as the wire protocol —
 * so an operator poking at the collection sees nothing surprising.
 *
 * <p>The duplicate-{@code _id} path is detected via the Mongo duplicate key code (11000),
 * which the driver surfaces as {@code MongoWriteException} (or
 * {@code MongoBulkWriteException}).
 */
final class MongoTransferOperationJournal implements TransferOperationJournal {

    private final MongoCollection<Document> collection;

    MongoTransferOperationJournal(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /** Creates the secondary indexes; {@code _id} already has a unique index. */
    static void ensureIndexes(MongoCollection<Document> collection) {
        collection.createIndex(Indexes.ascending("player_uuid", "state"),
                new IndexOptions().name("player_state_idx"));
        collection.createIndex(Indexes.ascending("updated_at"),
                new IndexOptions().name("updated_at_idx"));
    }

    @Override
    public void recordCreated(TransferOperation operation) throws DuplicateOperationException {
        try {
            collection.insertOne(toDocument(operation));
        } catch (com.mongodb.MongoWriteException e) {
            if (isDuplicateKey(e)) {
                throw new DuplicateOperationException(operation.operationId(),
                        "operation " + operation.operationId() + " is already recorded", e);
            }
            throw new PersistenceException("failed to record operation " + operation.operationId(), e);
        } catch (com.mongodb.MongoBulkWriteException e) {
            if (e.getWriteErrors().stream().anyMatch(MongoTransferOperationJournal::isDuplicateKey)) {
                throw new DuplicateOperationException(operation.operationId(),
                        "operation " + operation.operationId() + " is already recorded", e);
            }
            throw new PersistenceException("failed to record operation " + operation.operationId(), e);
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("failed to record operation " + operation.operationId(), e);
        }
    }

    @Override
    public boolean update(TransferOperation operation) {
        try {
            var result = collection.updateOne(
                    Filters.eq("_id", operation.operationId().toString()),
                    mutableUpdates(operation));
            return result.getMatchedCount() == 1;
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("failed to update operation " + operation.operationId(), e);
        }
    }

    @Override
    public boolean compareAndSetState(UUID operationId, OperationState expectedState, TransferOperation updated) {
        try {
            var result = collection.updateOne(
                    Filters.and(
                            Filters.eq("_id", operationId.toString()),
                            Filters.eq("state", expectedState.wireName())),
                    mutableUpdates(updated));
            // matched against the expected state — modification count can be 0 for identical rewrites
            return result.getMatchedCount() == 1;
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("compareAndSetState failed for operation " + operationId, e);
        }
    }

    @Override
    public Optional<TransferOperation> findById(UUID operationId) {
        try {
            Document document = collection.find(Filters.eq("_id", operationId.toString())).first();
            return document == null ? Optional.empty() : Optional.of(mapDocument(document));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("findById failed for operation " + operationId, e);
        }
    }

    @Override
    public List<TransferOperation> findActiveForPlayer(UUID playerUuid) {
        try {
            return collection
                    .find(Filters.and(
                            Filters.eq("player_uuid", playerUuid.toString()),
                            Filters.nin("state", List.of("committed", "failed"))))
                    .sort(Sorts.ascending("created_at"))
                    .into(new ArrayList<>())
                    .stream()
                    .map(MongoTransferOperationJournal::mapDocument)
                    .toList();
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("findActiveForPlayer failed for player " + playerUuid, e);
        }
    }

    @Override
    public List<TransferOperation> listRecent(int limit) {
        int clamped = Math.max(1, Math.min(1000, limit));
        try {
            return collection
                    .find()
                    .sort(Sorts.descending("updated_at"))
                    .limit(clamped)
                    .into(new ArrayList<>())
                    .stream()
                    .map(MongoTransferOperationJournal::mapDocument)
                    .toList();
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("listRecent failed", e);
        }
    }

    private static boolean isDuplicateKey(com.mongodb.MongoWriteException e) {
        return e.getError().getCode() == 11000;
    }

    private static boolean isDuplicateKey(com.mongodb.bulk.BulkWriteError e) {
        return e.getCode() == 11000;
    }

    private static Document toDocument(TransferOperation operation) {
        return new Document("_id", operation.operationId().toString())
                .append("operation_id", operation.operationId().toString())
                .append("player_uuid", operation.playerUuid().toString())
                .append("source_server", operation.sourceServer())
                .append("target_server", operation.targetServer())
                .append("source_dimension", operation.sourceDimension().key())
                .append("target_dimension", operation.targetDimension().key())
                .append("cause", operation.cause().wireName())
                .append("state", operation.state().wireName())
                .append("target_x", operation.targetPosition().x())
                .append("target_y", operation.targetPosition().y())
                .append("target_z", operation.targetPosition().z())
                .append("target_yaw", operation.targetPosition().yaw())
                .append("target_pitch", operation.targetPosition().pitch())
                .append("created_at", new Date(operation.createdAt().toEpochMilli()))
                .append("updated_at", new Date(operation.updatedAt().toEpochMilli()))
                .append("error_code", operation.error() == null ? null : operation.error().wireName())
                .append("error_message", operation.errorMessage());
    }

    private static List<org.bson.conversions.Bson> mutableUpdates(TransferOperation operation) {
        return List.of(
                Updates.set("state", operation.state().wireName()),
                Updates.set("target_x", operation.targetPosition().x()),
                Updates.set("target_y", operation.targetPosition().y()),
                Updates.set("target_z", operation.targetPosition().z()),
                Updates.set("target_yaw", operation.targetPosition().yaw()),
                Updates.set("target_pitch", operation.targetPosition().pitch()),
                Updates.set("updated_at", new Date(operation.updatedAt().toEpochMilli())),
                Updates.set("error_code", operation.error() == null ? null : operation.error().wireName()),
                Updates.set("error_message", operation.errorMessage()));
    }

    private static TransferOperation mapDocument(Document document) {
        Position position = new Position(
                document.getDouble("target_x"),
                document.getDouble("target_y"),
                document.getDouble("target_z"),
                document.getDouble("target_yaw"),
                document.getDouble("target_pitch"));
        String errorName = document.getString("error_code");
        Date created = document.getDate("created_at");
        Date updated = document.getDate("updated_at");
        return new TransferOperation(
                UUID.fromString(document.getString("_id")),
                UUID.fromString(document.getString("player_uuid")),
                document.getString("source_server"),
                document.getString("target_server"),
                DimensionId.of(document.getString("source_dimension")),
                DimensionId.of(document.getString("target_dimension")),
                TransferCause.fromWireName(document.getString("cause")),
                position,
                OperationState.fromWireName(document.getString("state")),
                created == null ? Instant.EPOCH : created.toInstant(),
                updated == null ? Instant.EPOCH : updated.toInstant(),
                errorName == null ? null : TransferError.fromWireName(errorName),
                document.getString("error_message"));
    }
}
