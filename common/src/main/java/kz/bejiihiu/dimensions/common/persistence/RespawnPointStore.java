package kz.bejiihiu.dimensions.common.persistence;

import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;

import java.util.Optional;
import java.util.UUID;

/**
 * Durable, per-player respawn-point records. Storage failures surface as
 * {@link PersistenceException}; there is no duplicate-key error here because the
 * store is keyed by player and {@link #upsert} is defined as insert-or-replace.
 *
 * <p>The record is what powers the End exit portal (and any future "where does this
 * player respawn" question). Both back ends key the row on {@code player_uuid};
 * the SQL implementation enforces it with the primary key, the Mongo implementation
 * with the document {@code _id}. There is deliberately no history — a new bed /
 * anchor overwrites the previous record, matching vanilla semantics.
 */
public interface RespawnPointStore {

    /**
     * Inserts or replaces the player's respawn record (one row per player, always).
     * Concurrent upserts for the same player serialize on the storage key; the last
     * writer wins.
     */
    void upsert(RespawnPointRecord point);

    /** The current record for the player, or empty when none is stored. */
    Optional<RespawnPointRecord> find(UUID playerUuid);

    /**
     * Removes the player's record. Returns true when a record existed.
     * Used on anchor/bed destruction flows and by admin tooling; the primary flow
     * never deletes — it lets the record go stale instead (validation on use).
     */
    boolean delete(UUID playerUuid);
}
