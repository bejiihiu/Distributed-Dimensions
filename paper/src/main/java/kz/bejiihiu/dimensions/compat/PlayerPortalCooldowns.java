package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Vanilla portal-cooldown semantics for the player entity itself.
 *
 * <p>Players are living entities: an ordinary pass stamps the PDC with the 300-tick
 * (15 s) ordinary-entity window ({@link PortalCooldownRules#ORDINARY_MILLIS}). As in
 * vanilla, a player riding any vehicle is part of a player-carried assembly — the whole
 * assembly never cools down — so both the check and the stamp are exempt while
 * {@code getVehicle() != null}. The vehicle's own cooldown is governed separately by the
 * entity-transfer rules (also exempt as a player-carrying assembly).
 */
public final class PlayerPortalCooldowns {

    private PlayerPortalCooldowns() {
    }

    /** A player riding any vehicle never cools down — the assembly rules, not per-entity ones, apply. */
    public static boolean exempt(@NotNull Player player) {
        return player.getVehicle() != null;
    }

    /** Whether the player is inside its cooldown window right now. */
    public static boolean isCoolingDown(@NotNull CooldownStore store, @NotNull Player player, long nowMillis) {
        if (exempt(player)) {
            return false;
        }
        return PortalCooldownRules.isCoolingDown(player, store.lastPassMillis(player), nowMillis);
    }

    /** Stamps the arrival pass. Skipped for players riding a vehicle (vanilla assembly semantics). */
    public static void stampArrival(@NotNull CooldownStore store, @NotNull Player player,
            @NotNull UUID operationId, long nowMillis) {
        if (exempt(player)) {
            return;
        }
        store.stamp(player, operationId, nowMillis);
    }
}
