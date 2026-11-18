package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.util.EulerAngle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorStandSerializerTest {

    @Test
    void fullStandReadCapturesPoseAndFlags() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("stand")
                .answer("getPassengers", List.of())
                .answer("hasBasePlate", false)
                .answer("isVisible", false)
                .answer("hasArms", true)
                .answer("isSmall", true)
                .answer("isMarker", false)
                .answer("canMove", false)
                .answer("canTick", true)
                .answer("getBodyPose", new EulerAngle(0.1, 0.2, 0.3))
                .answer("getHeadPose", new EulerAngle(1.1, 1.2, 1.3))
                .answer("getDisabledSlots", Set.of(org.bukkit.inventory.EquipmentSlot.HAND));
        ArmorStand stand = ProxyEntities.entity(ArmorStand.class, EntityType.ARMOR_STAND, canned);

        JsonObject fields = new ArmorStandSerializer().read(stand, new EntityTransferMetrics());
        assertEquals(false, fields.get("basePlate").getAsBoolean());
        assertEquals(true, fields.get("small").getAsBoolean());
        assertEquals(false, fields.get("canMove").getAsBoolean());
        assertEquals(0.1, fields.getAsJsonObject("bodyPose").get("x").getAsDouble(), 0.0001);
        assertEquals(1.2, fields.getAsJsonObject("headPose").get("y").getAsDouble(), 0.0001);
        assertEquals("HAND", fields.getAsJsonArray("disabledSlots").get(0).getAsString());
    }

    @Test
    void poseAndFlagsRoundTrip() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("stand-out")
                .answer("getPassengers", List.of());
        ArmorStand stand = ProxyEntities.entity(ArmorStand.class, EntityType.ARMOR_STAND, canned);

        JsonObject fields = new JsonObject();
        fields.addProperty("basePlate", false);
        fields.addProperty("marker", true);
        fields.addProperty("canTick", true);
        JsonObject body = new JsonObject();
        body.addProperty("x", 0.75);
        body.addProperty("y", -0.5);
        body.addProperty("z", 0.125);
        fields.add("bodyPose", body);
        com.google.gson.JsonArray slots = new com.google.gson.JsonArray();
        slots.add("HEAD");
        slots.add("NOT_A_REAL_SLOT");
        fields.add("disabledSlots", slots);

        EntityTransferMetrics metrics = new EntityTransferMetrics();
        new ArmorStandSerializer().write(stand, fields, metrics);
        assertEquals(List.of(false), canned.calls("setBasePlate"));
        assertEquals(List.of(true), canned.calls("setMarker"));
        org.bukkit.util.EulerAngle bodyAngle = (org.bukkit.util.EulerAngle) canned.calls("setBodyPose").get(0);
        assertEquals(0.75, bodyAngle.getX(), 0.0001);
        // the unknown slot was dropped, the real one kept
        assertEquals(1, metrics.snapshot().get(EntityTransferMetrics.DEGRADED).longValue());
    }
}
