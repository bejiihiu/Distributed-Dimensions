package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ZombieHorse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorseSerializerTest {

    @Test
    void horseStateRoundTrips() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("horse-out")
                .answer("getPassengers", List.of())
                .answer("getJumpStrength", 0.9)
                .answer("getDomestication", 4)
                .answer("getMaxDomestication", 7)
                .answer("isTamed", true)
                .answer("getOwnerUniqueId", UUID.randomUUID())
                .answer("getMaxHealth", 30.0)
                .answer("getSaddle", null);
        ZombieHorse horse = ProxyEntities.entity(ZombieHorse.class, EntityType.ZOMBIE_HORSE, out);

        JsonObject fields = new HorseSerializer().read(horse, new EntityTransferMetrics());
        assertEquals(0.9, fields.get("jumpStrength").getAsDouble(), 0.0001);
        assertEquals(4, fields.get("domestication").getAsInt());
        assertEquals(7, fields.get("maxDomestication").getAsInt());
        assertTrue(fields.get("tamed").getAsBoolean());
        assertTrue(fields.has("owner"));

        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("jumpStrength", 0.3);
        writeFields.addProperty("domestication", 1);
        writeFields.addProperty("maxDomestication", 10);
        writeFields.addProperty("tamed", true);
        writeFields.addProperty("maxHealth", 30.0);
        writeFields.addProperty("health", 15.0);
        new HorseSerializer().write(horse, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(0.3), out.calls("setJumpStrength").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(1), out.calls("setDomestication").stream().map(a -> ((Number) a).intValue()).toList());
        assertEquals(List.of(10), out.calls("setMaxDomestication").stream().map(a -> ((Number) a).intValue()).toList());
    }

    @Test
    void kindMappingCoversTheFamily() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("kinds")
                .answer("getPassengers", List.of());
        org.bukkit.entity.Horse regular = ProxyEntities.entity(
                org.bukkit.entity.Horse.class, EntityType.HORSE, canned);
        org.bukkit.entity.Donkey donkey = ProxyEntities.entity(
                org.bukkit.entity.Donkey.class, EntityType.DONKEY, canned);
        org.bukkit.entity.Mule mule = ProxyEntities.entity(
                org.bukkit.entity.Mule.class, EntityType.MULE, canned);
        org.bukkit.entity.Llama llama = ProxyEntities.entity(
                org.bukkit.entity.Llama.class, EntityType.LLAMA, canned);
        org.bukkit.entity.SkeletonHorse skeleton = ProxyEntities.entity(
                org.bukkit.entity.SkeletonHorse.class, EntityType.SKELETON_HORSE, canned);

        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.HORSE, SerializerRegistry.kindFor(regular));
        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.DONKEY, SerializerRegistry.kindFor(donkey));
        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MULE, SerializerRegistry.kindFor(mule));
        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.LLAMA, SerializerRegistry.kindFor(llama));
        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.ZOMBIE_HORSE,
                SerializerRegistry.kindFor(ProxyEntities.entity(ZombieHorse.class, EntityType.ZOMBIE_HORSE, canned)));
        assertEquals(kz.bejiihiu.dimensions.common.model.EntityPayloadKind.SKELETON_HORSE, SerializerRegistry.kindFor(skeleton));
    }

    @Test
    void llamaCarriesColorAndStrength() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("llama-out")
                .answer("getPassengers", List.of())
                .answer("getColor", org.bukkit.entity.Llama.Color.BROWN)
                .answer("getStrength", 3)
                .answer("getMaxHealth", 30.0)
                .answer("getDecor", null)
                .answer("isTamed", false)
                .answer("getDomestication", 2)
                .answer("getMaxDomestication", 5)
                .answer("getJumpStrength", 0.7);
        org.bukkit.entity.Llama llama = ProxyEntities.entity(org.bukkit.entity.Llama.class, EntityType.LLAMA, out);

        JsonObject fields = new HorseSerializer().read(llama, new EntityTransferMetrics());
        assertEquals("BROWN", fields.get("llamaColor").getAsString());
        assertEquals(3, fields.get("strength").getAsInt());

        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("llamaColor", "CREAMY");
        writeFields.addProperty("strength", 5);
        writeFields.addProperty("maxHealth", 30.0);
        writeFields.addProperty("health", 20.0);
        new HorseSerializer().write(llama, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(org.bukkit.entity.Llama.Color.CREAMY), out.calls("setColor"));
        assertEquals(List.of(5), out.calls("setStrength").stream().map(a -> ((Number) a).intValue()).toList());
    }
}
