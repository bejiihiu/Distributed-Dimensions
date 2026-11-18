package kz.bejiihiu.dimensions.paper;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the Paper/Folia backend plugin. Feature modules get wired in
 * here as they land; until then this is a minimal but real plugin lifecycle.
 */
public final class DistributedDimensionsPaperPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("Distributed Dimensions enabled");
    }

    @Override
    public void onDisable() {
        getSLF4JLogger().info("Distributed Dimensions disabled");
    }
}
