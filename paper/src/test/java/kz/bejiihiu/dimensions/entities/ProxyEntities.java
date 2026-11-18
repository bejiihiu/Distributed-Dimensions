package kz.bejiihiu.dimensions.entities;

import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dynamic-proxy fake entities for the serializer and graph tests — no mock frameworks,
 * consistent with the repo's test conventions. Each proxy answers the small whitelist of
 * methods the serializers actually call; anything else returns a sane default or null.
 *
 * <p>Write-side assertions read back the calls recorded by the handler.
 */
final class ProxyEntities {

    private ProxyEntities() {
    }

    /** Canned responses keyed by method name. Anything absent uses {@link #defaultValue}. */
    static final class Canned implements InvocationHandler {
        private final Map<String, Object> answers = new LinkedHashMap<>();
        private final Map<String, List<Object>> calls = new LinkedHashMap<>();
        private final String label;

        Canned(String label) {
            this.label = label;
        }

        Canned answer(String method, Object value) {
            answers.put(method, value);
            return this;
        }

        List<Object> calls(String method) {
            return calls.getOrDefault(method, List.of());
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            calls.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(
                    args == null ? List.of() : (args.length == 1 ? args[0] : List.of(args)));
            return switch (name) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                case "toString" -> label;
                default -> answers.containsKey(name) ? answers.get(name) : defaultValue(method.getReturnType());
            };
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (List.class.isAssignableFrom(type)) {
                return List.of();
            }
            if (Set.class.isAssignableFrom(type)) {
                return Set.of();
            }
            if (Map.class.isAssignableFrom(type)) {
                return Map.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0;
    }

    /** A proxy of {@code iface} with the canned answers; getType/getUniqueId are preset. */
    @SuppressWarnings("unchecked")
    static <T extends Entity> T entity(Class<T> iface, EntityType type, Canned canned) {
        canned.answer("getType", type);
        canned.answer("getUniqueId", UUID.randomUUID());
        canned.answer("isValid", true);
        canned.answer("isDead", false);
        canned.answer("getEntityId", 1);
        return (T) Proxy.newProxyInstance(ProxyEntities.class.getClassLoader(), new Class<?>[] { iface }, canned);
    }

    /** A proxy over several interfaces (e.g. minecart + inventory holder behavior). */
    @SuppressWarnings("unchecked")
    static <T extends Entity> T entity(List<Class<?>> interfaces, EntityType type, Canned canned) {
        canned.answer("getType", type);
        canned.answer("getUniqueId", UUID.randomUUID());
        canned.answer("isValid", true);
        canned.answer("isDead", false);
        canned.answer("getEntityId", 1);
        return (T) Proxy.newProxyInstance(ProxyEntities.class.getClassLoader(), interfaces.toArray(Class[]::new), canned);
    }

    /** Build a passenger list of proxies. */
    static List<Entity> passengers(Entity... passengers) {
        return List.of(passengers);
    }
}
