package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Updates;
import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import kz.bejiihiu.dimensions.common.model.EntityPayloadState;
import kz.bejiihiu.dimensions.common.model.EntityTransferPayload;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * MongoDB implementation of the payload store (sync driver; indexing is ensured on
 * open). Documents are field-per-column with the same lower-snake names as the SQL
 * schema. {@code _id} is {@code op_id + ":" + seq}, which makes the row identity — and
 * the source-side idempotency of a re-sent operation — the document key itself.
 *
 * <p>{@link #claimOp} flips docs one at a time with per-document compare-and-set
 * ({@code findOneAndUpdate} filtered on the pending state); if the tally does not cover
 * the whole operation the flipped docs are moved to {@code FAILED} and the claim fails
 * closed — an assembly is never claimed partially.
 */
final class MongoEntityPayloadStore implements EntityPayloadStore {

    private final MongoCollection<Document> collection;

    MongoEntityPayloadStore(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    static void ensureIndexes(MongoCollection<Document> collection) {
        collection.createIndex(
                Indexes.ascending("op_id", "seq"),
                new IndexOptions().name("op_seq_idx"));
        collection.createIndex(
                Indexes.ascending("state", "target_server", "created_at"),
                new IndexOptions().name("state_target_created_idx"));
        collection.createIndex(
                Indexes.ascending("state", "created_at"),
                new IndexOptions().name("state_created_idx"));
    }

    private static String documentId(String opId, int seq) {
        return opId + ":" + seq;
    }

    @Override
    public void insertAll(List<EntityTransferPayload> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Document> docs = new ArrayList<>(rows.size());
        for (EntityTransferPayload row : rows) {
            docs.add(toDocument(row));
        }
        try {
            collection.insertMany(docs);
        } catch (com.mongodb.MongoBulkWriteException e) {
            throw new PersistenceException(
                    "failed to insert " + rows.size() + " payload row(s) for op " + rows.get(0).opId()
                            + " (duplicate (op_id, seq) rejected)", e);
        } catch (MongoException e) {
            throw new PersistenceException(
                    "failed to insert " + rows.size() + " payload row(s) for op " + rows.get(0).opId(), e);
        }
    }

    @Override
    public List<EntityTransferPayload> findByOpId(String opId) {
        try {
            List<EntityTransferPayload> rows = new ArrayList<>();
            collection.find(Filters.eq("op_id", opId)).sort(com.mongodb.client.model.Sorts.ascending("seq"))
                    .forEach(doc -> rows.add(mapDocument(doc)));
            return List.copyOf(rows);
        } catch (MongoException e) {
            throw new PersistenceException("findByOpId failed for op " + opId, e);
        }
    }

    @Override
    public int countForOp(String opId) {
        try {
            return (int) collection.countDocuments(Filters.eq("op_id", opId));
        } catch (MongoException e) {
            throw new PersistenceException("countForOp failed for op " + opId, e);
        }
    }

    @Override
    public List<String> findPendingOpIds(String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<Document> grouped = collection.aggregate(
                            List.of(
                                    com.mongodb.client.model.Aggregates.match(
                                            Filters.and(Filters.eq("state", EntityPayloadState.PENDING.wireName()),
                                                    Filters.eq("target_server", targetServer))),
                                    com.mongodb.client.model.Aggregates.group("$op_id",
                                            com.mongodb.client.model.Accumulators.min("created", "$created_at")),
                                    com.mongodb.client.model.Aggregates.sort(
                                            com.mongodb.client.model.Sorts.ascending("created")),
                                    com.mongodb.client.model.Aggregates.limit(limit)))
                    .into(new ArrayList<>());
            List<String> ids = new ArrayList<>(grouped.size());
            for (Document group : grouped) {
                ids.add(group.getString("_id"));
            }
            return List.copyOf(ids);
        } catch (MongoException e) {
            throw new PersistenceException("findPendingOpIds failed for target " + targetServer, e);
        }
    }

    @Override
    public List<String> findPendingOpIdsForPlayer(UUID playerUuid, String targetServer, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            List<Document> docs = collection.find(
                            Filters.and(
                                    Filters.eq("state", EntityPayloadState.PENDING.wireName()),
                                    Filters.eq("target_server", targetServer),
                                    Filters.eq("player_uuid", playerUuid.toString())))
                    .sort(com.mongodb.client.model.Sorts.ascending("created_at"))
                    .limit(Math.max(1, Math.min(limit * 4, 1000)))
                    .into(new ArrayList<>());
            List<String> ids = new ArrayList<>();
            for (Document doc : docs) {
                String opId = doc.getString("op_id");
                if (!ids.contains(opId)) {
                    ids.add(opId);
                    if (ids.size() >= limit) {
                        break;
                    }
                }
            }
            return List.copyOf(ids);
        } catch (MongoException e) {
            throw new PersistenceException("findPendingOpIdsForPlayer failed for target " + targetServer, e);
        }
    }

    @Override
    public List<EntityTransferPayload> claimOp(String opId, String targetServer, Instant now) {
        long nowMillis = now.toEpochMilli();
        try {
            long total = collection.countDocuments(Filters.eq("op_id", opId));
            if (total == 0) {
                return List.of();
            }
            // flip pending rows of this op one at a time; the state filter is the CAS
            List<String> flippedIds = new ArrayList<>();
            List<EntityTransferPayload> claimed = new ArrayList<>();
            List<Document> pending = collection.find(
                            Filters.and(
                                    Filters.eq("op_id", opId),
                                    Filters.eq("target_server", targetServer),
                                    Filters.eq("state", EntityPayloadState.PENDING.wireName())))
                    .sort(com.mongodb.client.model.Sorts.ascending("seq"))
                    .into(new ArrayList<>());
            for (Document doc : pending) {
                Document updated = collection.findOneAndUpdate(
                        Filters.and(
                                Filters.eq("_id", doc.getString("_id")),
                                Filters.eq("state", EntityPayloadState.PENDING.wireName())),
                        Updates.combine(
                                Updates.set("state", EntityPayloadState.CLAIMED.wireName()),
                                Updates.set("updated_at", new Date(nowMillis))),
                        new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                if (updated != null) {
                    flippedIds.add(updated.getString("_id"));
                    claimed.add(mapDocument(updated));
                }
            }
            if (claimed.size() == total) {
                claimed.sort(Comparator.comparingInt(EntityTransferPayload::seq));
                return List.copyOf(claimed);
            }
            if (flippedIds.isEmpty()) {
                // not pending for us at all — not ours to touch
                return List.of();
            }
            // inconsistent op: fail exactly the rows this call claimed
            collection.updateMany(
                    Filters.in("_id", flippedIds),
                    Updates.combine(
                            Updates.set("state", EntityPayloadState.FAILED.wireName()),
                            Updates.set("updated_at", new Date(nowMillis))));
            return List.of();
        } catch (MongoException e) {
            throw new PersistenceException("claimOp failed for op " + opId, e);
        }
    }

    @Override
    public int deleteForOp(String opId) {
        try {
            return (int) collection.deleteMany(Filters.eq("op_id", opId)).getDeletedCount();
        } catch (MongoException e) {
            throw new PersistenceException("deleteForOp failed for op " + opId, e);
        }
    }

    @Override
    public int failOp(String opId, Instant now) {
        try {
            return (int) collection.updateMany(
                            Filters.and(
                                    Filters.eq("op_id", opId),
                                    Filters.in("state", List.of(
                                            EntityPayloadState.PENDING.wireName(),
                                            EntityPayloadState.CLAIMED.wireName()))),
                            Updates.combine(
                                    Updates.set("state", EntityPayloadState.FAILED.wireName()),
                                    Updates.set("updated_at", new Date(now.toEpochMilli()))))
                    .getModifiedCount();
        } catch (MongoException e) {
            throw new PersistenceException("failOp failed for op " + opId, e);
        }
    }

    @Override
    public int expireStaleClaimed(Instant claimedBefore, Instant now) {
        try {
            return (int) collection.updateMany(
                            Filters.and(
                                    Filters.eq("state", EntityPayloadState.CLAIMED.wireName()),
                                    Filters.lt("updated_at", new Date(claimedBefore.toEpochMilli()))),
                            Updates.combine(
                                    Updates.set("state", EntityPayloadState.FAILED.wireName()),
                                    Updates.set("updated_at", new Date(now.toEpochMilli()))))
                    .getModifiedCount();
        } catch (MongoException e) {
            throw new PersistenceException("expireStaleClaimed failed", e);
        }
    }

    @Override
    public int deleteFailedOlderThan(Instant cutoff) {
        try {
            return (int) collection.deleteMany(
                            Filters.and(
                                    Filters.eq("state", EntityPayloadState.FAILED.wireName()),
                                    Filters.lt("created_at", new Date(cutoff.toEpochMilli()))))
                    .getDeletedCount();
        } catch (MongoException e) {
            throw new PersistenceException("deleteFailedOlderThan failed", e);
        }
    }

    private static Document toDocument(EntityTransferPayload row) {
        Document doc = new Document();
        doc.put("_id", documentId(row.opId(), row.seq()));
        doc.put("op_id", row.opId());
        doc.put("seq", row.seq());
        doc.put("kind", row.kind().wireName());
        doc.put("target_server", row.targetServer());
        doc.put("target_dimension", row.targetDimension());
        doc.put("player_uuid", row.playerUuid() == null ? null : row.playerUuid().toString());
        doc.put("payload_json", row.payloadJson());
        doc.put("state", row.state().wireName());
        doc.put("created_at", new Date(row.createdAt().toEpochMilli()));
        doc.put("updated_at", new Date(row.updatedAt().toEpochMilli()));
        return doc;
    }

    private static EntityTransferPayload mapDocument(Document doc) {
        String playerUuid = doc.getString("player_uuid");
        Date created = doc.getDate("created_at");
        Date updated = doc.getDate("updated_at");
        Number seq = (Number) doc.getOrDefault("seq", 0);
        return new EntityTransferPayload(
                doc.getString("op_id"),
                seq.intValue(),
                EntityPayloadKind.fromWireName(doc.getString("kind")),
                doc.getString("target_server"),
                doc.getString("target_dimension"),
                playerUuid == null ? null : UUID.fromString(playerUuid),
                doc.getString("payload_json"),
                EntityPayloadState.fromWireName(doc.getString("state")),
                created == null ? Instant.EPOCH : created.toInstant(),
                updated == null ? Instant.EPOCH : updated.toInstant());
    }
}
