package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.compat.PdcCooldownStore;
import kz.bejiihiu.dimensions.paper.PaperServices;
import kz.bejiihiu.dimensions.paper.end.BedAnchorValidator;
import kz.bejiihiu.dimensions.paper.end.EndArrivalPlanResolver;
import kz.bejiihiu.dimensions.paper.end.EndPortalListener;
import kz.bejiihiu.dimensions.paper.end.RespawnRecorder;
import kz.bejiihiu.dimensions.mirage.MirageService;
import kz.bejiihiu.dimensions.paper.transfer.BukkitTransferEventDispatcher;
import kz.bejiihiu.dimensions.paper.transfer.BukkitWorldLookup;
import kz.bejiihiu.dimensions.paper.transfer.OwnBackendEnvironment;
import kz.bejiihiu.dimensions.paper.transfer.PaperTransferBackend;
import kz.bejiihiu.dimensions.paper.transfer.PluginMessageTransferChannel;
import kz.bejiihiu.dimensions.paper.transfer.PortalTransferService;
import kz.bejiihiu.dimensions.paper.transfer.TransferArrivalListener;
import kz.bejiihiu.dimensions.paper.transfer.TransferArrivalService;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.function.Predicate;

/**
 * Wires the transfer orchestration plus both portal families into the backend: the End
 * portals (End module), the Nether portals, the shared source/target pipeline, the channel,
 * and the respawn reconciliation. Called once from plugin enable, after the
 * {@link PaperServices} holder is registered.
 *
 * <p>One {@link TransferArrivalService} serves both families; the plan resolver routes
 * END_PORTAL operations into the respawn-aware end logic and NETHER_PORTAL operations into
 * the portal search/create pipeline ({@link NetherArrivalExecutor}). The channel is
 * installed here; transfers that occur before install cannot happen (no portal events fire
 * before world load, and the module is installed at enable).
 */
public final class NetherPortalModule {

    private NetherPortalModule() {
    }

    public static void install(JavaPlugin plugin, PaperServices services) {
        plugin.getSLF4JLogger().info("nether portals: enabled={} (the vanilla delay and vanilla search algorithms are honored in full — see docs/nether-portals.md)",
                services.config().portals().nether().enabled());
        BukkitTransferEventDispatcher dispatcher = new BukkitTransferEventDispatcher(plugin);
        PluginMessageTransferChannel channel = new PluginMessageTransferChannel(plugin);
        PdcCooldownStore cooldowns = new PdcCooldownStore(plugin);
        PaperTransferBackend backend = new PaperTransferBackend(services, dispatcher, channel, plugin);

        PortalTransferService transfers = new PortalTransferService(backend, Clock.systemUTC());

        EndArrivalPlanResolver endResolver = new EndArrivalPlanResolver(backend);
        NetherArrivalPlanResolver resolver = new NetherArrivalPlanResolver(endResolver);
        BedAnchorValidator bedAnchor = new BedAnchorValidator(backend);
        NetherCausePositionValidator validator = new NetherCausePositionValidator(bedAnchor);

        NetherArrivalExecutor netherArrivals =
                new NetherArrivalExecutor(backend, new PaperNetherPortalStore(), cooldowns);
        TransferArrivalService arrivals =
                new TransferArrivalService(backend, Clock.systemUTC(), resolver, validator, netherArrivals);

        channel.install(transfers);

        // every backend spawns all three dimension stems, so the platform respawn world is
        // an overworld-typed stray on nether/end backends — resolve the world hosting this
        // backend's own dimension instead (mirages excluded), falling back per the lookup chain
        MirageService mirage = MirageService.current().orElse(null);
        Predicate<World> isMirage = world -> mirage != null && mirage.isMirage(world);
        Environment ownEnvironment = OwnBackendEnvironment.resolve(services);

        plugin.getServer().getPluginManager().registerEvents(new RespawnRecorder(backend), plugin);
        plugin.getServer().getPluginManager().registerEvents(new EndPortalListener(backend, transfers), plugin);
        plugin.getServer().getPluginManager().registerEvents(new NetherPortalListener(backend, transfers, cooldowns), plugin);
        plugin.getServer().getPluginManager().registerEvents(new TransferArrivalListener(arrivals,
                new BukkitWorldLookup(() -> ownEnvironment, isMirage)), plugin);
    }
}
