package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DegradedAndItemSerializerTest {

    @Test
    void itemReadCapturesPickupState() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("item")
                .answer("getPassengers", List.of())
                .answer("getItemStack", null)          // no NBT round-trip on a bare JVM
                .answer("getPickupDelay", 42)
                .answer("isUnlimitedLifetime", true)
                .answer("canMobPickup", false)
                .answer("canPlayerPickup", true)
                .answer("willAge", false)
                .answer("getHealth", 5)
                .answer("getOwner", UUID.randomUUID())
                .answer("getThrower", null);
        Item item = ProxyEntities.entity(Item.class, EntityType.ITEM, canned);

        JsonObject fields = new ItemSerializer().read(item, new EntityTransferMetrics());
        assertEquals(42, fields.get("pickupDelay").getAsInt());
        assertTrue(fields.get("unlimitedLifetime").getAsBoolean());
        assertFalse(fields.get("canMobPickup").getAsBoolean());
        assertFalse(fields.get("willAge").getAsBoolean());
        assertTrue(fields.has("owner"));
        assertFalse(fields.has("thrower"));
    }

    @Test
    void itemWriteAppliesPickupState() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("item-out")
                .answer("getPassengers", List.of());
        Item item = ProxyEntities.entity(Item.class, EntityType.ITEM, out);
        JsonObject fields = new JsonObject();
        fields.addProperty("pickupDelay", 999);
        fields.addProperty("unlimitedLifetime", true);
        fields.addProperty("canMobPickup", false);
        fields.addProperty("willAge", false);
        new ItemSerializer().write(item, fields, new EntityTransferMetrics());
        assertEquals(List.of(999), out.calls("setPickupDelay").stream().map(a -> ((Number) a).intValue()).toList());
        assertEquals(List.of(true), out.calls("setUnlimitedLifetime"));
        assertEquals(List.of(false), out.calls("setCanMobPickup"));
        assertEquals(List.of(false), out.calls("setWillAge"));
    }

    @Test
    void degradedSerializerWritesBaseAndCountsDegraded() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("unknown-mob")
                .answer("getPassengers", List.of())
                .answer("getMaxHealth", 10.0);
        Zombie zombie = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, out);
        JsonObject fields = new JsonObject();
        fields.addProperty("health", 4.0);
        fields.addProperty("maxHealth", 10.0);
        fields.addProperty("fireTicks", 60);
        fields.addProperty("silent", true);
        EntityTransferMetrics metrics = new EntityTransferMetrics();
        new DegradedSerializer().write(zombie, fields, metrics);
        assertEquals(List.of(4.0), out.calls("setHealth").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(60), out.calls("setFireTicks").stream().map(a -> ((Number) a).intValue()).toList());
        assertEquals(List.of(true), out.calls("setSilent"));
    }

    @Test
    void degradedReadIncrementsTheDegradedCounter() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("unknown-mob2")
                .answer("getPassengers", List.of())
                .answer("getMaxHealth", 10.0)
                .answer("getHealth", 9.0);
        Zombie zombie = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, out);
        EntityTransferMetrics metrics = new EntityTransferMetrics();
        new DegradedSerializer().read(zombie, metrics);
        assertEquals(1L, metrics.snapshot().get(EntityTransferMetrics.DEGRADED).longValue());
    }
}
