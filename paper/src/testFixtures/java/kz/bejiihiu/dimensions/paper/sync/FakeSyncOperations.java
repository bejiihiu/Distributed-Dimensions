package kz.bejiihiu.dimensions.paper.sync;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic test double for the sync gate. Defaults to an immediately-completed
 * ready report; can be switched to a degraded report, an immediate timeout, deferred
 * mode (future held open so the test can complete or fail it mid-flight, e.g. a player
 * disconnecting while the sync wait is pending), or a synchronously throwing
 * {@code awaitSync}. Records every awaited player UUID. No live server, no mocking
 * framework.
 */
public final class FakeSyncOperations implements SyncOperations {

    /** A confirmed sync report: {@code degraded=false}. */
    public static SyncReport readyReport() {
        return new SyncReport("fake-sync", 1L, false);
    }

    /** An unconfirmed sync report: the contract allows it to proceed, flagged. */
    public static SyncReport degradedReport() {
        return new SyncReport("fake-sync", 3L, true);
    }

    private volatile SyncReport report = readyReport();
    private volatile TimeoutReason timeoutReason;
    private volatile boolean throwOnAwait;
    private volatile boolean deferred;
    private final List<UUID> awaited = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new CopyOnWriteArrayList<>();

    private record Pending(Player player, CompletableFuture<SyncReport> future) {
    }

    /** Normal completion with this report (default is an immediately-completed ready report). */
    public void setReport(SyncReport report) {
        this.report = java.util.Objects.requireNonNull(report, "report");
        this.timeoutReason = null;
    }

    /** Exceptional completion with {@link SyncTimeoutException} for the reason, immediately on await. */
    public void setTimeout(TimeoutReason reason) {
        this.timeoutReason = java.util.Objects.requireNonNull(reason, "reason");
    }

    /** {@code awaitSync} itself throws — a broken integration implementation. */
    public void setThrowOnAwait(boolean throwOnAwait) {
        this.throwOnAwait = throwOnAwait;
    }

    /** Holds every future open until the test completes or fails it explicitly. */
    public void setDeferred(boolean deferred) {
        this.deferred = deferred;
    }

    @Override
    public CompletableFuture<SyncReport> awaitSync(Player player) {
        awaited.add(player.getUniqueId());
        if (throwOnAwait) {
            throw new IllegalStateException("test: awaitSync exploded");
        }
        CompletableFuture<SyncReport> future = new CompletableFuture<>();
        if (deferred) {
            pending.add(new Pending(player, future));
            return future;
        }
        complete(future, player);
        return future;
    }

    /** Players for which awaitSync was called, in call order. */
    public List<UUID> awaitedPlayers() {
        return List.copyOf(awaited);
    }

    /** Completes every held future normally with {@code report}. */
    public void completePending() {
        for (Pending held : pending) {
            held.future().complete(report);
        }
        pending.clear();
    }

    /** Completes every held future exceptionally with {@link SyncTimeoutException}. */
    public void failPending(TimeoutReason reason) {
        for (Pending held : pending) {
            held.future().completeExceptionally(
                    new SyncTimeoutException(held.player().getUniqueId(), reason));
        }
        pending.clear();
    }

    private void complete(CompletableFuture<SyncReport> future, Player player) {
        TimeoutReason reason = timeoutReason;
        if (reason != null) {
            future.completeExceptionally(new SyncTimeoutException(player.getUniqueId(), reason));
            return;
        }
        future.complete(report);
    }
}
