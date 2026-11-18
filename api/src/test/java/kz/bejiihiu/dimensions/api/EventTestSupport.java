package kz.bejiihiu.dimensions.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Minimal interface proxies of bukkit entity types for unit tests — just enough surface for
 * event payload assertions. Not usable as a stub for behaviour; every method returns a trivial
 * default.
 */
public final class EventTestSupport {

    private EventTestSupport() {
    }

    public static Player player() {
        return (Player) proxy(Player.class);
    }

    public static Entity entity() {
        return (Entity) proxy(Entity.class);
    }

    private static Object proxy(Class<?> type) {
        return Proxy.newProxyInstance(EventTestSupport.class.getClassLoader(), new Class<?>[]{type}, new DefaultHandler());
    }

    private static final class DefaultHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("toString")) {
                return "test-proxy:" + method.getReturnType().getSimpleName();
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("equals")) {
                return proxy == args[0];
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == UUID.class) {
                return UUID.randomUUID();
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class || returnType == short.class
                    || returnType == int.class || returnType == long.class) {
                return 0;
            }
            if (returnType == float.class || returnType == double.class) {
                return 0d;
            }
            if (returnType.isEnum()) {
                return returnType.getEnumConstants()[0];
            }
            return null;
        }
    }
}
