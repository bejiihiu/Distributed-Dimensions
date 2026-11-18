package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.paper.scheduler.Schedulers;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The player-join fast path: an assembly that travels with a player is claimed the moment
 * the player lands, instead of waiting for the next global poll tick. All storage work
 * happens on the async executor — the join event thread never blocks on the database.
 */
public final class EntityJoinListener implements Listener {

    private final EntityArrivalService arrivals;
    private final Schedulers schedulers;
    private final Supplier<String> ownServer;
    private final BooleanSupplier enabled;

    public EntityJoinListener(EntityArrivalService arrivals, Schedulers schedulers,
            Supplier<String> ownServer, BooleanSupplier enabled) {
        this.arrivals = arrivals;
        this.schedulers = schedulers;
        this.ownServer = ownServer;
        this.enabled = enabled;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        UUID player = event.getPlayer().getUniqueId();
        String me = ownServer.get();
        if (me == null) {
            return;   // module already logged the missing name once at install
        }
        schedulers.runAsync(() -> arrivals.claimForPlayer(player, me));
    }
}
