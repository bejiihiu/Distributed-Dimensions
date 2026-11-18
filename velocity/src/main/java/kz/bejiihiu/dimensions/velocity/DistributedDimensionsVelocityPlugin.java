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
import kz.bejiihiu.dimensions.common.config.ConfigurationException;
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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Entry point of the Velocity companion. The proxy owns connection routing and request
 * validation only; game semantics live on the backends. The constructor performs nothing
 * but field injection - everything is wired in {@link #onProxyInitialize}.
 *
 * <p>Boot order on {@code ProxyInitializeEvent}:
 * <ol>
 *   <li>register the {@code distributeddimensions:transfer} channel;</li>
 *   <li>load {@code plugins/distributeddimensions/config.yml} via the common configuration
 *       service (bundled defaults on first start). A total load failure - even the bundled
 *       defaults failing env expansion, e.g. {@code database.password: ${DB_PASSWORD}} with
 *       no {@code DB_PASSWORD} in the service environment - never takes the plugin down:
 *       boot continues in DEGRADED (diagnostic) mode;</li>
 *   <li>transfer pipeline and listeners are registered regardless of configuration health.
 *       In DEGRADED mode every {@code dimension_transfer_request} is refused with an
 *       explicit {@code UNAVAILABLE} result naming the cause; routing is never half-open.
 *       {@code /ddproxy reload} re-reads the file/environment; a clean load re-enables
 *       routing with no restart;</li>
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
    private final EnvLookup env;

    // assigned in onProxyInitialize; volatile-ish usage is fine within the event thread
    private volatile boolean channelRegistered;
    private TransferOperations operations;

    @Inject
    public DistributedDimensionsVelocityPlugin(Logger logger, ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this(logger, proxy, dataDirectory, EnvLookup.system());
    }

    // package-private: tests boot the plugin against a controlled environment lookup
    DistributedDimensionsVelocityPlugin(Logger logger, ProxyServer proxy, Path dataDirectory, EnvLookup env) {
        this.logger = logger;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.env = env;
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

        // 2. configuration, loaded once here; a total load failure degrades the plugin, it
        //    does not kill it (see class javadoc). Components below are wired unconditionally.
        ConfigurationService configuration = new ConfigurationService(
                Environment.resolve(dataDirectory), env, logger::warn);
        String bootFailure = null;
        try {
            configuration.load();
        } catch (RuntimeException e) {
            // ConfigurationException: even the bundled-defaults fallback failed (e.g. the
            // ${VAR} expansion of database.password with no variable in the environment).
            // Anything else is treated the same way - degraded, never propagated.
            // The single SEVERE line with the actionable fix is logged below, once.
            bootFailure = DegradedReasons.sanitize(e.getMessage());
        }

        // 3. transfer pipeline
        operations = new TransferOperations(Clock.systemUTC());
        VelocityTransferPlatform platform = new VelocityTransferPlatform(proxy, logger);
        TransferCoordinator coordinator = new TransferCoordinator(platform, operations,
                configuration::isHealthy,
                () -> configuration.config().network().dimensions(),
                () -> configuration.config().network().transfer().timeout(),
                degradedReasonSupplier(configuration, bootFailure),
                logger, CHANNEL);

        proxy.getEventManager().register(this, new PluginMessageListener(CHANNEL, coordinator, platform, operations.stats(), logger));
        proxy.getEventManager().register(this, new DisconnectListener(coordinator, logger));

        // 4. admin surface (native command; distributeddimensions.admin, default console)
        PluginAdminServices services = new PluginAdminServices(configuration, operations,
                () -> channelRegistered, logger);
        CommandMeta meta = proxy.getCommandManager().metaBuilder("ddproxy").plugin(this).build();
        proxy.getCommandManager().register(meta, new DdProxyCommand(services));

        boolean healthy = configuration.isHealthy();
        if (healthy) {
            logger.info("configuration loaded (config-version {})", configuration.config().configVersion());
            logger.info("Distributed Dimensions enabled (channel {})", CHANNEL.getId());
            return;
        }
        String reason = liveDegradedReason(configuration, bootFailure);
        logger.error("*** DEGRADED MODE *** configuration failed to load: {} - "
                        + "set DB_PASSWORD (or set database.password to \"\" for no credentials) - "
                        + "the proxy enables in diagnostic mode until then. "
                        + "Every transfer is refused with UNAVAILABLE; run '/ddproxy status' for the full reason "
                        + "and '/ddproxy reload' after fixing the environment or config.yml",
                reason == null ? "unknown cause (see the log above)" : reason);
        logger.error("Distributed Dimensions enabled in DEGRADED mode (channel {}): transfers are disabled, "
                + "fix the configuration and run '/ddproxy reload'", CHANNEL.getId());
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

    /**
     * Builds the live supplier of the failure reason for the fail-closed gate. Reads the
     * service's live state so a {@code /ddproxy reload} that fixes the failure stops
     * reporting the boot-time cause immediately; the boot-time capture is used only when
     * the service holds no live health detail (a raw boot failure it never recorded).
     */
    private static Supplier<String> degradedReasonSupplier(ConfigurationService configuration, String bootFailure) {
        return () -> liveDegradedReason(configuration, bootFailure);
    }

    private static String liveDegradedReason(ConfigurationService configuration, String bootFailure) {
        Optional<ConfigurationException> live = configuration.health();
        if (live.isPresent()) {
            return DegradedReasons.sanitize(live.get().getMessage());
        }
        if (configuration.isHealthy()) {
            return null; // routing is open; the supplier is unused
        }
        return bootFailure; // raw boot failure: nothing live was recorded
    }
}
