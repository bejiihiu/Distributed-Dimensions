package kz.bejiihiu.dimensions.paper;

import org.bukkit.World;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal {@link World} proxies for tests that only need identity-ish accessors
 * ({@code getUID}, {@code getName}, {@code getWorldName}) so code under test can
 * pass worlds around. Any other method throws {@link UnsupportedOperationException} —
 * if a test trips over one, it is touching real world state and must be rewritten.
 *
 * <p>The UID is derived deterministically from the name
 * ({@code nameUUIDFromBytes(name)}), so tickets created against the same name
 * produce identical keys across tests.
 */
public final class TestWorlds {

    private TestWorlds() {
    }

    /** A world proxy with a stable UID derived from {@code name}. */
    public static World create(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        UUID uid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUID":
                    return uid;
                case "getName":
                case "getWorldName":
                    return name;
                case "hashCode":
                    return uid.hashCode();
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "toString":
                    return "TestWorld[" + name + "]";
                default:
                    throw new UnsupportedOperationException(
                            "TestWorlds proxy refuses " + method.getName() + " — tests must not touch world state");
            }
        };
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }
}
