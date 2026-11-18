package kz.bejiihiu.dimensions.paper;

import org.bukkit.entity.Minecart;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Minimal {@link Minecart} proxies for tests that need "a vehicle exists" — e.g. the
 * player-riding-exempt portal-cooldown path. Any method beyond identity throws
 * {@link UnsupportedOperationException}: a test tripping one is touching real vehicle state.
 */
public final class TestVehicles {

    private TestVehicles() {
    }

    public static Minecart minecart() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.randomUUID();
            case "toString" -> "TestVehicle[minecart]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "isValid" -> true;
            case "isDead" -> false;
            default -> throw new UnsupportedOperationException(
                    "TestVehicles proxy refuses " + method.getName());
        };
        return (Minecart) Proxy.newProxyInstance(
                TestVehicles.class.getClassLoader(), new Class<?>[] { Minecart.class }, handler);
    }
}
