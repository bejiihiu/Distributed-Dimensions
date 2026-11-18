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
 * {@code teleport}/{@code teleportAsync}), and a recorded {@code sendMessage} log. Any
 * other method throws {@link UnsupportedOperationException} — a test tripping one is
 * touching real server state and must be rewritten.
 */
public final class FakePlayers {

    private FakePlayers() {
    }

    /** One recorded teleport request (sync or async). */
    public record TeleportCall(Location location, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
    }

    /** A proxy player with a deterministic identity. */
    public static Player create(UUID uuid, Location location, boolean online) {
        return create(uuid, location, online, null);
    }

    /** A proxy player riding the given vehicle (or standing, when {@code vehicle} is null). */
    public static Player create(UUID uuid, Location location, boolean online, @org.jetbrains.annotations.Nullable org.bukkit.entity.Entity vehicle) {
        List<String> messages = new CopyOnWriteArrayList<>();
        MutableLocation current = new MutableLocation(location);
        List<TeleportCall> teleports = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean teleportFailure = new java.util.concurrent.atomic.AtomicBoolean(false);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return uuid;
                case "isOnline":
                    return online;
                case "getVehicle":
                    return vehicle;
                case "getPassengers":
                    return java.util.List.of();
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
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause =
                            args.length >= 2 && args[1] instanceof org.bukkit.event.player.PlayerTeleportEvent.TeleportCause c
                                    ? c : org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN;
                    teleports.add(new TeleportCall(to == null ? null : to.clone(), cause));
                    if (teleportFailure.get()) {
                        return false;
                    }
                    current.set(to == null ? null : to.clone());
                    return true;
                }
                case "teleportAsync": {
                    Location to = (Location) args[0];
                    org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause =
                            args.length >= 2 && args[1] instanceof org.bukkit.event.player.PlayerTeleportEvent.TeleportCause c
                                    ? c : org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN;
                    teleports.add(new TeleportCall(to == null ? null : to.clone(), cause));
                    if (teleportFailure.get()) {
                        return java.util.concurrent.CompletableFuture.completedFuture(Boolean.FALSE);
                    }
                    current.set(to == null ? null : to.clone());
                    return java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE);
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
        TELEPORTS.put(player, teleports);
        TELEPORT_FAILURES.put(player, teleportFailure);
        return player;
    }

    /** All messages sent to this proxy player, oldest first. */
    public static List<String> messages(Player player) {
        return List.copyOf(MESSAGES.getOrDefault(player, List.of()));
    }

    /** Recorded teleport requests (sync or async), oldest first. */
    public static List<TeleportCall> teleportCalls(Player player) {
        return List.copyOf(TELEPORTS.getOrDefault(player, List.of()));
    }

    /** Make every subsequent teleport (sync or async) report failure. Default: succeed. */
    public static void setTeleportFailure(Player player, boolean failure) {
        java.util.concurrent.atomic.AtomicBoolean flag = TELEPORT_FAILURES.get(player);
        if (flag == null) {
            throw new IllegalArgumentException("not a FakePlayers proxy");
        }
        flag.set(failure);
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
    private static final java.util.Map<Player, List<TeleportCall>> TELEPORTS =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Player, java.util.concurrent.atomic.AtomicBoolean> TELEPORT_FAILURES =
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
