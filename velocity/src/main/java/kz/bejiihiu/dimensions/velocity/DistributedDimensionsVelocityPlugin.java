package kz.bejiihiu.dimensions.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import kz.bejiihiu.dimensions.common.config.ConfigurationService;
import kz.bejiihiu.dimensions.common.config.EnvLookup;
import kz.bejiihiu.dimensions.common.config.Environment;
import kz.bejiihiu.dimensions.velocity.command.DdProxyCommand;
import kz.bejiihiu.dimensions.velocity.command.PluginAdminServices;
import kz.bejiihiu.dimensions.velocity.listener.DisconnectListener;
import kz.bejiihiu.dimensions.velocity.listener.PluginMessageListener;
import kz.bejiihiu.dimensions.velocity.transfer.TransferCoordinator;
import kz.bejiihiu.dimensions.velocity.transfer.TransferOperations;
import kz.bejiihiu.dimensions.velocity.transfer.VelocityTransferPlatform;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Entry point of the Velocity companion. The proxy owns connection routing and request
 * validation only; game semantics live on the backends. The constructor performs nothing
 * but field injection - everything is wired in {@link #onProxyInitialize}.
 *
 * <p>Boot order on {@code ProxyInitializeEvent}:
 * <ol>
 *   <li>register the {@code distributeddimensions:transfer} channel;</li>
 *   <li>load {@code plugins/distributeddimensions/config.yml} via the common configuration
 *       service (bundled defaults on first start);</li>
 *   <li>record the configuration health. When the file is broken the plugin enables in a
 *       fail-closed mode - routing is refused with a clear error back to the backend - and
 *       never half-opens; {@code /ddproxy reload} re-enables routing after a clean load;</li>
 *   <li>register the plugin-message and disconnect listeners and the {@code /ddproxy} command.</li>
 * </ol>
 *
 * <p>No database is required on the proxy by design: all operation state is in-memory with
 * ~10 minute TTL bounds. Durable state belongs to the backends' shared database.
 */
@Plugin(
        id = "distributeddimensions",
        name = "Distributed Dimensions",
        version = BuildConstants.VERSION,
        description = "Paper/Folia + Velocity plugin that distributes the three Minecraft dimensions across independent backend servers.",
        authors = {"bejiihiu.xs"}
)
public class DistributedDimensionsVelocityPlugin {

    /** The one channel this plugin speaks on; backends must use the same identifier. */
    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("distributeddimensions", "transfer");

    private final Logger logger;
    private final ProxyServer proxy;
    private final Path dataDirectory;

    // assigned in onProxyInitialize; volatile-ish usage is fine within the event thread
    private volatile boolean channelRegistered;
    private TransferOperations operations;

    @Inject
    public DistributedDimensionsVelocityPlugin(Logger logger, ProxyServer proxy, @DataDirectory Path dataDirectory) {
        // injection fields only - the construction phase stays untouched
        this.logger = logger;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        // 1. register the channel first so nothing races ahead of us
        try {
            proxy.getChannelRegistrar().register(CHANNEL);
            channelRegistered = true;
        } catch (RuntimeException e) {
            channelRegistered = false;
            logger.error("failed to register channel {}: {} - backends will not get through", CHANNEL.getId(), e.toString());
        }

        // 2. configuration, loaded once here; health is recorded, not assumed
        ConfigurationService configuration = new ConfigurationService(
                Environment.resolve(dataDirectory), EnvLookup.system(), logger::warn);
        configuration.load();
        if (configuration.isHealthy()) {
            logger.info("configuration loaded (config-version {})", configuration.config().configVersion());
        } else {
            configuration.health().ifPresent(error ->
                    logger.error("configuration is broken: {} - transfers DISABLED until a clean load; "
                                    + "routing will be refused with a clear error to the backends", error.getMessage()));
        }

        // 3. transfer pipeline
        operations = new TransferOperations(Clock.systemUTC());
        VelocityTransferPlatform platform = new VelocityTransferPlatform(proxy, logger);
        TransferCoordinator coordinator = new TransferCoordinator(platform, operations,
                configuration::isHealthy,
                () -> configuration.config().network().dimensions(),
                () -> configuration.config().network().transfer().timeout(),
                logger, CHANNEL);

        proxy.getEventManager().register(this, new PluginMessageListener(CHANNEL, coordinator, platform, operations.stats(), logger));
        proxy.getEventManager().register(this, new DisconnectListener(coordinator, logger));

        // 4. admin surface (native command; distributeddimensions.admin, default console)
        PluginAdminServices services = new PluginAdminServices(configuration, operations,
                () -> channelRegistered, logger);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("ddproxy").plugin(this).build();
        proxy.getCommandManager().register(meta, new DdProxyCommand(services));

        if (configuration.isHealthy()) {
            logger.info("Distributed Dimensions enabled (channel {})", CHANNEL.getId());
        } else {
            logger.error("Distributed Dimensions enabled in DEGRADED mode (channel {}): transfers are disabled, "
                    + "fix the configuration and run '/ddproxy reload'", CHANNEL.getId());
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        try {
            proxy.getChannelRegistrar().unregister(CHANNEL);
        } catch (RuntimeException e) {
            logger.warn("failed to unregister channel {}: {}", CHANNEL.getId(), e.toString());
        }
        if (operations != null) {
            logger.info("Distributed Dimensions disabled (in-flight at shutdown: {})", operations.pendingSize());
        } else {
            logger.info("Distributed Dimensions disabled");
        }
    }
}
