package kz.bejiihiu.dimensions.entities;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * The computed landing state for one assembly on the target server: absolute location
 * (including yaw/pitch), the velocity to apply, and whether the destination portal was
 * found, created by us, or not required (end arrivals). Diagnostics get the disposition.
 */
public record EntityPlacement(Location location, Vector velocity, Disposition disposition) {

    public enum Disposition { PORTAL_FOUND, PORTAL_CREATED, IDEAL_HINT, END_PLATFORM, WORLD_SPAWN }
}
