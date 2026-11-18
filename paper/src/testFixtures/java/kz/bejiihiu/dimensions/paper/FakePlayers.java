package kz.bejiihiu.dimensions.paper;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal {@link Player} proxies for tests. Supports exactly the accessors the transfer
 * and respawn flows use: identity, online flag, mutable location (teleported through
 * {@code teleport}), and a recorded {@code sendMessage} log. Any other method throws
 * {@link UnsupportedOperationException} — a test tripping one is touching real server
 * state and must be rewritten.
 */
public final class FakePlayers {

    private FakePlayers() {
    }

    /** A proxy player with a deterministic identity. */
    public static Player create(UUID uuid, Location location, boolean online) {
        List<String> messages = new CopyOnWriteArrayList<>();
        MutableLocation current = new MutableLocation(location);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return uuid;
                case "isOnline":
                    return online;
                case "getLocation":
                    return current.get();
                case "getWorld":
                    return current.get() == null ? null : current.get().getWorld();
                case "getName":
                    return "FakePlayer";
                case "getDisplayName":
                    return "FakePlayer";
                case "isValid":
                    return true;
                case "isDead":
                    return false;
                case "teleport": {
                    Location to = (Location) args[0];
                    current.set(to == null ? null : to.clone());
                    return true;
                }
                case "sendMessage": {
                    if (args != null && args.length == 1 && args[0] != null) {
                        messages.add(args[0].toString());
                    }
                    return null;
                }
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "hashCode":
                    return uuid.hashCode();
                case "toString":
                    return "FakePlayer[" + uuid + "]";
                default:
                    throw new UnsupportedOperationException(
                            "FakePlayer refuses " + method.getName() + " - test touches server state it must not");
            }
        };
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
        LOCATIONS.put(player, current);
        MESSAGES.put(player, messages);
        return player;
    }

    /** All messages sent to this proxy player, oldest first. */
    public static List<String> messages(Player player) {
        return List.copyOf(MESSAGES.getOrDefault(player, List.of()));
    }

    /** The current (mutable) location of this proxy player. */
    public static Location location(Player player) {
        MutableLocation current = LOCATIONS.get(player);
        return current == null ? null : current.get();
    }

    // proxy instances cannot carry fields — side tables keyed by identity
    private static final java.util.Map<Player, MutableLocation> LOCATIONS =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Player, List<String>> MESSAGES =
            new java.util.WeakHashMap<>();

    private static final class MutableLocation {
        private Location location;

        private MutableLocation(Location location) {
            this.location = location == null ? null : location.clone();
        }

        private Location get() {
            return location == null ? null : location.clone();
        }

        private void set(Location location) {
            this.location = location;
        }
    }
}
