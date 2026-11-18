package kz.bejiihiu.dimensions.common.model;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One row of the payload table — one node of an entity assembly's passenger graph.
 *
 * <p>Rows are written pre-order (root first, passengers in mount order); {@code seq}
 * is the position in that order and {@code parentSeq} (carried inside {@code payloadJson})
 * links each node to its parent. The whole operation's rows travel together: the target
 * backend claims them with a single compare-and-set, spawns in {@code seq} order and
 * re-mounts with {@code setPassenger}.
 *
 * <p>{@code payloadJson} is opaque to this layer; its schema is owned by the Paper
 * module's codec. {@code playerUuid} is null for standalone entity operations and is
 * used only for the join fast-path probe.
 */
public record EntityTransferPayload(
        String opId,
        int seq,
        EntityPayloadKind kind,
        String targetServer,
        String targetDimension,
        @Nullable UUID playerUuid,
        String payloadJson,
        EntityPayloadState state,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern OP_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // 48 KiB cap: MySQL's TEXT column tops out at 64 KiB, and we want headroom for multi-item
    // nbt blobs before the insert fails. A too-big row aborts the op at the source (entity
    // keeps its local state) instead of truncating silently.
    private static final int MAX_JSON_LENGTH = 48 * 1024;

    public EntityTransferPayload {
        Objects.requireNonNull(opId, "opId");
        if (!OP_ID.matcher(opId).matches()) {
            throw new IllegalArgumentException("opId must be a lower-case UUID string, got '" + opId + "'");
        }
        if (seq < 0 || seq >= 1024) {
            throw new IllegalArgumentException("seq must be in [0, 1024), got " + seq);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetServer, "targetServer");
        if (targetServer.isBlank() || targetServer.length() > 64) {
            throw new IllegalArgumentException("targetServer must be 1..64 chars, got '" + targetServer + "'");
        }
        Objects.requireNonNull(targetDimension, "targetDimension");
        if (targetDimension.isBlank() || targetDimension.length() > 128) {
            throw new IllegalArgumentException("targetDimension must be 1..128 chars, got '" + targetDimension + "'");
        }
        Objects.requireNonNull(payloadJson, "payloadJson");
        if (payloadJson.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("payloadJson exceeds " + MAX_JSON_LENGTH + " chars");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /** A fresh pending row for the given operation. */
    public static EntityTransferPayload pending(String opId, int seq, EntityPayloadKind kind,
            String targetServer, String targetDimension, @Nullable UUID playerUuid,
            String payloadJson, Instant now) {
        return new EntityTransferPayload(opId, seq, kind, targetServer, targetDimension, playerUuid,
                payloadJson, EntityPayloadState.PENDING, now, now);
    }

    /** Same row moved to {@code nextState} with a fresh {@code updatedAt}. */
    public EntityTransferPayload movedTo(EntityPayloadState nextState, Instant now) {
        return new EntityTransferPayload(opId, seq, kind, targetServer, targetDimension, playerUuid,
                payloadJson, nextState, createdAt, now);
    }
}
