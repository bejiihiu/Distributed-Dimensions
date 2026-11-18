package kz.bejiihiu.dimensions.paper.nether;

import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.GameRule;
import org.jetbrains.annotations.NotNull;

/**
 * Reads the nether portal delay gamerules from a live source world. Resolution happens on
 * every call so a runtime {@code /gamerule} change is picked up on the next portal pass;
 * a gamerule that cannot be read (the registry unavailable, another plugin removed the
 * vanilla rule) degrades to the vanilla constants instead of throwing — the portal must
 * stay usable even then.
 */
public final class BukkitNetherPortalGamerules implements NetherPortalGamerules {

    private final World world;

    public BukkitNetherPortalGamerules(@NotNull World world) {
        this.world = world;
    }

    @Override
    public int playersNetherPortalDefaultDelay() {
        try {
            return NetherPortalGamerules.sanitize(
                    world.getGameRuleValue(GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY),
                    VANILLA_DEFAULT_DELAY);
        } catch (Throwable t) {
            return VANILLA_DEFAULT_DELAY;
        }
    }

    @Override
    public int playersNetherPortalCreativeDelay() {
        try {
            return NetherPortalGamerules.sanitize(
                    world.getGameRuleValue(GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY),
                    VANILLA_CREATIVE_DELAY);
        } catch (Throwable t) {
            return VANILLA_CREATIVE_DELAY;
        }
    }
}
