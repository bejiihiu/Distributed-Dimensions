package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSerializerTest {

    @Test
    void mobStateRoundTrips() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("zombie-out")
                .answer("getPassengers", List.of())
                .answer("isAware", true)
                .answer("isPersistenceRequired", false)
                .answer("getMaxHealth", 20.0);
        Zombie zombie = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, out);

        JsonObject fields = new MobSerializer().read(zombie, new EntityTransferMetrics());
        assertTrue(fields.get("aware").getAsBoolean());
        assertFalse(fields.get("persistent").getAsBoolean());
        assertEquals(20.0, fields.get("maxHealth").getAsDouble(), 0.0001);

        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("aware", false);
        writeFields.addProperty("persistent", true);
        writeFields.addProperty("health", 11.0);
        writeFields.addProperty("maxHealth", 40.0);
        new MobSerializer().write(zombie, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(false), out.calls("setAware"));
        assertEquals(List.of(true), out.calls("setPersistenceRequired"));
        assertEquals(List.of(40.0), out.calls("setMaxHealth").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(11.0), out.calls("setHealth").stream().map(a -> ((Number) a).doubleValue()).toList());
    }

    @Test
    void healthIsClampedToMax() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("zombie-out2")
                .answer("getPassengers", List.of())
                .answer("getMaxHealth", 20.0);
        Zombie zombie = ProxyEntities.entity(Zombie.class, EntityType.ZOMBIE, out);

        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("health", 999.0);
        writeFields.addProperty("maxHealth", 20.0);
        new MobSerializer().write(zombie, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(20.0), out.calls("setHealth").stream().map(a -> ((Number) a).doubleValue()).toList());
    }

    @Test
    void animalsGoThroughTheSameMobPath() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("pig-out")
                .answer("getPassengers", List.of())
                .answer("isAware", true)
                .answer("isPersistenceRequired", false)
                .answer("getMaxHealth", 10.0);
        org.bukkit.entity.Pig pig = ProxyEntities.entity(org.bukkit.entity.Pig.class, EntityType.PIG, out);
        JsonObject fields = new MobSerializer().read(pig, new EntityTransferMetrics());
        assertTrue(fields.has("aware"));
        assertEquals(10.0, fields.get("maxHealth").getAsDouble(), 0.0001);
    }
}
