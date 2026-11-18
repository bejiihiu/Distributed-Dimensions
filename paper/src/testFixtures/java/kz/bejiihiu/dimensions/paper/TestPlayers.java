package kz.bejiihiu.dimensions.paper;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.util.UUID;

/**
 * Minimal {@link Player} proxies for tests that need identity, the online flag or
 * a name — nothing else. Any other method throws, like {@link TestWorlds}.
 */
public final class TestPlayers {

    private TestPlayers() {
    }

    /** A player proxy with the given UUID; {@code online} drives {@code isOnline()}. */
    public static Player create(UUID uuid, boolean online) {
        return create(uuid, online, "TestPlayer");
    }

    /** A player proxy with the given UUID, online flag and name. */
    public static Player create(UUID uuid, boolean online, String name) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "isOnline", "isValid" -> online;
            case "getName", "getDisplayName" -> name;
            case "hashCode" -> uuid.hashCode();
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "TestPlayer[" + name + "/" + uuid + "]";
            default -> throw new UnsupportedOperationException(
                    "TestPlayers proxy refuses " + method.getName() + " — tests must not touch game state");
        };
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] { Player.class }, handler);
    }
}
