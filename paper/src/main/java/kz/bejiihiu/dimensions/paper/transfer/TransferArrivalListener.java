package kz.bejiihiu.dimensions.paper.transfer;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Bridges Bukkit join lifecycle to {@link TransferArrivalService}. Registered once per
 * backend; inert for players without a pending operation.
 *
 * <p>{@code AsyncPlayerSpawnLocationEvent} fires in configuration phase — the player is
 * not in the world yet; we only decide and record the spawn. {@code PlayerJoinEvent}
 * fires when the player is (about to be) in the world; position work happens on the
 * entity's own scheduler from there.
 */
public final class TransferArrivalListener implements Listener {

    private final TransferArrivalService arrivals;
    private final ArrivalWorldLookup worlds;

    public TransferArrivalListener(TransferArrivalService arrivals, ArrivalWorldLookup worlds) {
        this.arrivals = java.util.Objects.requireNonNull(arrivals, "arrivals");
        this.worlds = java.util.Objects.requireNonNull(worlds, "worlds");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        UUID playerUuid = event.getConnection().getProfile().getId();
        if (playerUuid == null) {
            return;   // unauthenticated connection — never our business
        }
        Optional<TransferArrivalService.ArrivalClaim> claimed =
                arrivals.claimAndPlan(playerUuid, worlds);
        claimed.ifPresent(claim -> {
            if (claim.location() != null) {
                event.setSpawnLocation(claim.location());
            }
        });
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        arrivals.onJoin(player, worlds);
    }
}
