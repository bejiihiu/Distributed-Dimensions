package kz.bejiihiu.dimensions.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Entry point of the Velocity proxy plugin. Routing and the backend<->proxy
 * protocol get wired in here as they land; until then this is a minimal but
 * real proxy lifecycle.
 */
@Plugin(
        id = "distributeddimensions",
        name = "Distributed Dimensions",
        version = BuildConstants.VERSION,
        description = "Paper/Folia + Velocity plugin that distributes the three Minecraft dimensions across independent backend servers.",
        authors = {"bejiihiu.xs"}
)
public class DistributedDimensionsVelocityPlugin {

    private final Logger logger;

    @Inject
    public DistributedDimensionsVelocityPlugin(Logger logger) {
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("Distributed Dimensions enabled");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Distributed Dimensions disabled");
    }
}
