package kz.bejiihiu.dimensions.compat;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cooldown behavior per entity type — the rules table and the registry over an
 * in-memory store with an injected clock. No server involved.
 */
class PortalCooldownTest {

    private static Entity entity(Class<?>[] types, List<Entity> passengers) {
        UUID stableId = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> stableId;
            case "getPassengers" -> passengers;
            case "isValid" -> true;
            case "isDead" -> false;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "TestEntity";
            default -> defaultValue(method.getReturnType());
        };
        return (Entity) Proxy.newProxyInstance(
                Entity.class.getClassLoader(), types, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (List.class.isAssignableFrom(type)) {
                return List.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }

    private static Entity plain() {
        return entity(new Class<?>[] { Entity.class }, List.of());
    }

    private static Entity projectile() {
        return entity(new Class<?>[] { Projectile.class }, List.of());
    }

    private static Entity minecart() {
        return entity(new Class<?>[] { Minecart.class }, List.of());
    }

    private static Entity boat() {
        return entity(new Class<?>[] { Boat.class }, List.of());
    }

    private static Player playerPassenger() {
        return (Player) entity(new Class<?>[] { Player.class }, List.of());
    }

    @Test
    void ttlPerEntityType() {
        assertEquals(PortalCooldownRules.ORDINARY_MILLIS, PortalCooldownRules.cooldownMillis(plain()));
        assertEquals(PortalCooldownRules.PROJECTILE_MILLIS, PortalCooldownRules.cooldownMillis(projectile()));
        assertEquals(PortalCooldownRules.VEHICLE_MILLIS, PortalCooldownRules.cooldownMillis(minecart()));
        assertEquals(PortalCooldownRules.VEHICLE_MILLIS, PortalCooldownRules.cooldownMillis(boat()));
    }

    @Test
    void entityCarryingAPlayerNeverCoolsDown() {
        Entity withPlayer = entity(new Class<?>[] { Entity.class }, List.of(playerPassenger()));
        assertEquals(-1L, PortalCooldownRules.cooldownMillis(withPlayer));
        assertFalse(PortalCooldownRules.isCoolingDown(withPlayer, 1_000L, 2_000L));
    }

    @Test
    void registryReportsCooldownInsideWindowAndExpiryAfter() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        AtomicLong now = new AtomicLong(1_000_000L);
        PaperPortalRegistry registry = new PaperPortalRegistry(store, now::get);

        Entity cart = minecart();
        assertFalse(registry.isPortalCoolingDown(cart), "never stamped — nothing to cool down");

        registry.stampPass(cart, UUID.randomUUID());
        assertTrue(registry.isPortalCoolingDown(cart), "just stamped — inside the 500ms window");

        now.addAndGet(PortalCooldownRules.VEHICLE_MILLIS - 1);
        assertTrue(registry.isPortalCoolingDown(cart), "still inside the window at ttl-1ms");

        now.addAndGet(1);
        assertFalse(registry.isPortalCoolingDown(cart), "expired at exactly ttl");
    }

    @Test
    void projectileWindowIsShorterThanOrdinary() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        AtomicLong now = new AtomicLong(1_000_000L);
        PaperPortalRegistry registry = new PaperPortalRegistry(store, now::get);

        Entity arrow = projectile();
        registry.stampPass(arrow, UUID.randomUUID());
        now.addAndGet(PortalCooldownRules.PROJECTILE_MILLIS + 1);
        assertFalse(registry.isPortalCoolingDown(arrow));

        Entity sheep = plain();
        registry.stampPass(sheep, UUID.randomUUID());
        now.addAndGet(PortalCooldownRules.PROJECTILE_MILLIS + 1);
        assertTrue(registry.isPortalCoolingDown(sheep), "ordinary entity still cooling down");
    }

    @Test
    void inMemoryStoreRoundtrip() {
        InMemoryCooldownStore store = new InMemoryCooldownStore();
        Entity e = plain();
        UUID op = UUID.randomUUID();
        org.junit.jupiter.api.Assertions.assertNull(store.lastPassMillis(e));
        store.stamp(e, op, 42L);
        assertEquals(42L, store.lastPassMillis(e));
        assertEquals(op, store.lastOperationId(e));
    }
}
