package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.RespawnPointRecord;
import kz.bejiihiu.dimensions.paper.transfer.PortalTransferService;
import kz.bejiihiu.dimensions.paper.transfer.TransferBackend;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Optional;

/**
 * End entry / exit portal handling. Vanilla cannot switch servers, so the portal event is
 * always cancelled and the crossing is executed through the transfer pipeline — or
 * refused with a clear message (fail-closed) when the backend is degraded.
 *
 * <ul>
 *   <li><b>Overworld &rarr; End</b>: the end backend is the configured target, arrival is
 *       the obsidian platform at {@code (100.5, 49, 0.5)} facing west (Minecraft Wiki,
 *       Obsidian platform: platform at (100, 48, 0), players spawn at (100.5, 49, 0.5),
 *       1.16.2+ centered). Yaw 90 = west in Minecraft's rotation convention.</li>
 *   <li><b>End exit portal (The End &rarr; respawn point)</b>: the destination is the
 *       recorded respawn point — bed &rarr; overworld family backend (or the dimension
 *       recorded for that bed), respawn anchor &rarr; nether family backend — and the
 *       recorded dimension's backend via the configured routing. No record (or an
 *       unroutable one): the overworld backend, world spawn.</li>
 * </ul>
 *
 * <p>The event fires on the portal block's region thread; the whole source flow stays on
 * it (see {@link PortalTransferService} for the threading model).
 */
public final class EndPortalListener implements Listener {

    /** Obsidian platform arrival anchor: (100.5, 49, 0.5), facing west (yaw 90, pitch 0). */
    public static final Position END_ARRIVAL = new Position(100.5, 49.0, 0.5, 90.0, 0.0);

    private final TransferBackend backend;
    private final PortalTransferService transfers;

    public EndPortalListener(TransferBackend backend, PortalTransferService transfers) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.transfers = java.util.Objects.requireNonNull(transfers, "transfers");
    }

    // Runs after protection-style vetoes; honours their cancellation instead of
    // overriding a plugin that deliberately blocked the portal.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            return;
        }
        World world = event.getFrom().getWorld();
        if (world == null) {
            return;
        }
        switch (world.getEnvironment()) {
            case NORMAL -> handleEntry(event);
            case THE_END -> handleExit(event);
            default -> {
                // not our portal — a NETHER-side end portal or a custom dimension: hands off
            }
        }
    }

    private void handleEntry(PlayerPortalEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!backend.config().portals().end().enabled()) {
            refuse(player, "End portals are disabled on this network.");
            return;
        }
        PortalTransferService.Outcome outcome = transfers.transfer(
                player, DimensionId.OVERWORLD, DimensionId.THE_END, TransferCause.END_PORTAL, END_ARRIVAL);
        report(outcome, player);
    }

    private void handleExit(PlayerPortalEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!backend.config().portals().end().enabled()) {
            refuse(player, "The exit portal is disabled on this network.");
            return;
        }

        Optional<RespawnPointRecord> record;
        try {
            record = backend.respawnPoints().find(player.getUniqueId());
        } catch (RuntimeException e) {
            // cannot tell "no respawn point" from "store broken" — vanilla semantics when in
            // doubt: the player goes to world spawn of the overworld (never into a void)
            backend.diagnostics().accept("respawn store read failed on exit for player "
                    + player.getUniqueId() + ": " + e.getMessage() + " — taking the overworld world spawn");
            record = Optional.empty();
        }

        DimensionId targetDimension;
        Position targetPosition;
        if (record.isPresent()
                && backend.dimensions().serverFor(record.get().dimension()).isPresent()) {
            targetDimension = record.get().dimension();
            targetPosition = record.get().position();
        } else {
            if (record.isPresent()) {
                backend.diagnostics().accept("respawn record dimension " + record.get().dimension().key()
                        + " has no backend configured; falling back to the overworld world spawn");
            }
            targetDimension = DimensionId.OVERWORLD;
            // the journal carries a real position the source can vouch for (the portal the
            // player just entered); the target resolves the actual placement target-side
            Location from = event.getFrom();
            double x = finiteOr(from.getX(), 0.5);
            double y = finiteOr(from.getY(), 64.0);
            double z = finiteOr(from.getZ(), 0.5);
            targetPosition = new Position(x, y, z, from.getYaw(), from.getPitch());
        }

        PortalTransferService.Outcome outcome = transfers.transfer(
                player, DimensionId.THE_END, targetDimension, TransferCause.END_PORTAL, targetPosition);
        report(outcome, player);
    }

    private void report(PortalTransferService.Outcome outcome, Player player) {
        if (outcome instanceof PortalTransferService.Outcome.Refused refused) {
            refuse(player, refused.reason());
        }
        // Started: the proxy takes the player; Cancelled: the integration listener handled it
    }

    private void refuse(Player player, String reason) {
        backend.diagnostics().accept("end portal transfer refused for player " + player.getUniqueId()
                + ": " + reason);
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "You cannot travel there right now: " + reason + ". Please tell an administrator."));
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
