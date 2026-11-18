package kz.bejiihiu.dimensions.paper.sync;

import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * Entry point for the arrival flow: await the external sync plugin confirming the
 * player's data was applied on this backend.
 *
 * <p><b>Callers must never join()/get() the returned future</b> — the arrival flow
 * runs on region threads and blocking there violates the scheduler rules. Attach
 * continuations instead. On normal completion the future carries a {@link SyncReport}
 * (check {@link SyncReport#degraded()} — true means nothing was actually confirmed).
 * On exceptional completion it carries {@link SyncTimeoutException}; the transfer has
 * failed and the orchestration layer turns this into
 * {@code DimensionTransferFailedEvent} — this branch dispatches nothing public.
 */
public interface SyncOperations {

    /**
     * Await applied sync data for {@code player}. Never blocks; the returned future
     * completes on whatever thread drives the strategy.
     */
    CompletableFuture<SyncReport> awaitSync(Player player);
}
