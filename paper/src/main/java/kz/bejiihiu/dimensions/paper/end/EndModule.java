package kz.bejiihiu.dimensions.paper.end;

import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.transfer.BukkitTransferEventDispatcher;
import kz.bejiihiu.dimensions.paper.transfer.BukkitWorldLookup;
import kz.bejiihiu.dimensions.paper.transfer.PaperTransferBackend;
import kz.bejiihiu.dimensions.paper.transfer.PluginMessageTransferChannel;
import kz.bejiihiu.dimensions.paper.transfer.PortalTransferService;
import kz.bejiihiu.dimensions.paper.transfer.TransferArrivalListener;
import kz.bejiihiu.dimensions.paper.transfer.TransferArrivalService;

import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;

/**
 * Wires the End wave into the backend: the transfer orchestration (shared), the End
 * arrival plan/validator, the respawn recorder, the portal listener and the channel.
 * Called once from plugin enable, after the {@link PaperServices} holder is registered.
 *
 * <p>The channel is installed here; transfers that occur before install cannot happen
 * (no portal events fire before world load, and the module is installed at enable).
 */
public final class EndModule {

    private EndModule() {
    }

    public static void install(JavaPlugin plugin, PaperServices services) {
        BukkitTransferEventDispatcher dispatcher = new BukkitTransferEventDispatcher(plugin);
        PluginMessageTransferChannel channel = new PluginMessageTransferChannel(plugin);
        PaperTransferBackend backend = new PaperTransferBackend(services, dispatcher, channel, plugin);

        PortalTransferService transfers = new PortalTransferService(backend, Clock.systemUTC());
        EndArrivalPlanResolver resolver = new EndArrivalPlanResolver(backend);
        BedAnchorValidator validator = new BedAnchorValidator(backend);
        TransferArrivalService arrivals = new TransferArrivalService(backend, Clock.systemUTC(), resolver, validator);

        channel.install(transfers);

        plugin.getServer().getPluginManager().registerEvents(new RespawnRecorder(backend), plugin);
        plugin.getServer().getPluginManager().registerEvents(new EndPortalListener(backend, transfers), plugin);
        plugin.getServer().getPluginManager().registerEvents(new TransferArrivalListener(arrivals, new BukkitWorldLookup()), plugin);
    }
}
