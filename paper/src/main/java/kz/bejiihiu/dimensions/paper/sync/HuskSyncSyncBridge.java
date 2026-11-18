package kz.bejiihiu.dimensions.paper.sync;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import net.william278.husksync.event.BukkitSyncCompleteEvent;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Isolated holder for the only class in the plugin that names HuskSync types.
 * Instantiated solely after the presence/API checks in
 * {@link HuskSyncCompileIntegration} pass, so on servers without HuskSync this
 * class is never loaded and linkage stays safe.
 *
 * <p>Fires at {@link EventPriority#MONITOR} — observation only, never cancels or
 * modifies anything. The event is fired by HuskSync off the join handler when the
 * snapshot has actually been applied; note it also fires on admin-forced data
 * restores of online players, so completions are only attributed to waiters that
 * are actually pending.
 */
final class HuskSyncSyncBridge implements Listener {

    private final HuskSyncCompileIntegration integration;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    HuskSyncSyncBridge(HuskSyncCompileIntegration integration) {
        this.integration = integration;
    }

    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onSyncComplete(BukkitSyncCompleteEvent event) {
        try {
            net.william278.husksync.user.OnlineUser user = event.getUser();
            UUID uuid = user == null ? null : user.getUuid();
            if (uuid == null) {
                logBridgeFailureOnce(new IllegalStateException("event carried no user/uuid"));
                return;
            }
            integration.completeIfPending(uuid, SyncCause.EVENT);
        } catch (Throwable t) {
            logBridgeFailureOnce(t);
        }
    }

    private void logBridgeFailureOnce(Throwable t) {
        if (failureLogged.compareAndSet(false, true)) {
            integration.logger().warn(
                    "sync: HuskSync completion listener failed ({}). Pending waits will fail at the timeout; "
                            + "check that HuskSync matches the expected API (net.william278.husksync.event.BukkitSyncCompleteEvent).",
                    t.toString());
        }
    }
}
