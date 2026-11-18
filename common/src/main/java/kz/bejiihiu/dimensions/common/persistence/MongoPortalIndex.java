package kz.bejiihiu.dimensions.common.persistence;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB implementation of the portal index (sync driver). Same overlap semantics as the SQL
 * side; documents keep the lower-snake field names of the wire protocol.
 */
final class MongoPortalIndex implements PortalIndex {

    private final MongoCollection<Document> collection;

    MongoPortalIndex(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    static void ensureIndexes(MongoCollection<Document> collection) {
        collection.createIndex(Indexes.ascending("dimension", "inner_min_x"),
                new IndexOptions().name("dimension_inner_min_x_idx"));
        collection.createIndex(Indexes.ascending("dimension", "origin_x", "origin_z"),
                new IndexOptions().name("dimension_origin_idx"));
    }

    @Override
    public void upsert(PortalIndexEntry entry) {
        try {
            collection.replaceOne(
                    Filters.eq("_id", entry.portalId().toString()),
                    toDocument(entry),
                    new ReplaceOptions().upsert(true));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("failed to upsert portal " + entry.portalId(), e);
        }
    }

    @Override
    public Optional<PortalIndexEntry> findById(UUID portalId) {
        try {
            Document document = collection.find(Filters.eq("_id", portalId.toString())).first();
            return document == null ? Optional.empty() : Optional.of(mapDocument(document));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("findById failed for portal " + portalId, e);
        }
    }

    @Override
    public List<PortalIndexEntry> candidatesIn(DimensionId dimension, int minX, int maxX, int minZ, int maxZ) {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("empty search box [" + minX + "," + maxX + "] x [" + minZ + "," + maxZ + "]");
        }
        try {
            return collection
                    .find(Filters.and(
                            Filters.eq("dimension", dimension.key()),
                            Filters.gte("inner_max_x", minX),
                            Filters.lte("inner_min_x", maxX),
                            Filters.gte("inner_max_z", minZ),
                            Filters.lte("inner_min_z", maxZ)))
                    .sort(Sorts.ascending("origin_x", "origin_z"))
                    .into(new ArrayList<>())
                    .stream()
                    .map(MongoPortalIndex::mapDocument)
                    .toList();
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("candidatesIn failed for dimension " + dimension, e);
        }
    }

    @Override
    public boolean delete(UUID portalId) {
        try {
            return collection.deleteOne(Filters.eq("_id", portalId.toString())).getDeletedCount() == 1;
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("delete failed for portal " + portalId, e);
        }
    }

    @Override
    public long count(DimensionId dimension) {
        try {
            return collection.countDocuments(Filters.eq("dimension", dimension.key()));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("count failed for dimension " + dimension, e);
        }
    }

    private static Document toDocument(PortalIndexEntry entry) {
        return new Document("_id", entry.portalId().toString())
                .append("portal_id", entry.portalId().toString())
                .append("dimension", entry.dimension().key())
                .append("orientation", entry.orientation().wireName())
                .append("origin_x", entry.origin().x())
                .append("origin_y", entry.origin().y())
                .append("origin_z", entry.origin().z())
                .append("inner_min_x", entry.innerMin().x())
                .append("inner_min_y", entry.innerMin().y())
                .append("inner_min_z", entry.innerMin().z())
                .append("inner_max_x", entry.innerMax().x())
                .append("inner_max_y", entry.innerMax().y())
                .append("inner_max_z", entry.innerMax().z())
                .append("created_at", new Date(entry.createdAt().toEpochMilli()))
                .append("updated_at", new Date(entry.updatedAt().toEpochMilli()));
    }

    private static PortalIndexEntry mapDocument(Document document) {
        Date created = document.getDate("created_at");
        Date updated = document.getDate("updated_at");
        return new PortalIndexEntry(
                UUID.fromString(document.getString("_id")),
                DimensionId.of(document.getString("dimension")),
                PortalOrientation.fromWireName(document.getString("orientation")),
                new BlockPos(document.getInteger("origin_x"), document.getInteger("origin_y"), document.getInteger("origin_z")),
                new BlockPos(document.getInteger("inner_min_x"), document.getInteger("inner_min_y"), document.getInteger("inner_min_z")),
                new BlockPos(document.getInteger("inner_max_x"), document.getInteger("inner_max_y"), document.getInteger("inner_max_z")),
                created == null ? Instant.EPOCH : created.toInstant(),
                updated == null ? Instant.EPOCH : updated.toInstant());
    }
}
