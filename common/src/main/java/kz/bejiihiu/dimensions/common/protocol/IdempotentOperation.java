package kz.bejiihiu.dimensions.common.protocol;

import java.util.UUID;

/**
 * Idempotency contract for operations that may be retried end-to-end.
 *
 * <p>Implementations carry a single correlation id under whatever name the message schema
 * uses ({@code request_id}, {@code operation_id}, {@code query_id}). The contract:
 *
 * <ul>
 *   <li>Every requesting party mints exactly one id per logical operation and reuses it on
 *       every retry — a retry is not a new operation.</li>
 *   <li>Every receiving party keys its dedup state on this id:
 *     <ul>
 *       <li>First sight of an id — process and remember the outcome.</li>
 *       <li>Replay of a known id — return (or reconstruct) the recorded outcome; never
 *           re-execute the side effect.</li>
 *       <li>Unknown id, but the operation's window has expired — treat as unknown; do not
 *           guess.</li>
 *     </ul></li>
 *   <li>Ids are {@link UUID}s, so they are unique without coordination between backends.</li>
 * </ul>
 *
 * <p>This interface is the marker the proxy and backends code against; it exists so each
 * future message type can be classified as "idempotent operation" or not without reading
 * its javadoc.
 */
public interface IdempotentOperation {

    /** The operation's correlation id — a v4 UUID. Never null. */
    UUID requestId();
}
