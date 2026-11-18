package kz.bejiihiu.dimensions.paper.chunktickets;

/**
 * Platform-side effect of holding a chunk ticket. Abstracted so the bookkeeping
 * and refresh/expiry logic in {@link ChunkTicketService} is unit-testable without
 * a running server (the production implementation talks to
 * {@code World#addPluginChunkTicket} / {@code World#removePluginChunkTicket}).
 */
public interface ChunkTicketGateway {

    /** Ask the platform to hold a ticket for the chunk. True when the ticket exists afterwards. */
    boolean add(TicketKey key);

    /** Ask the platform to release the ticket. True when the ticket no longer exists afterwards. */
    boolean remove(TicketKey key);
}
