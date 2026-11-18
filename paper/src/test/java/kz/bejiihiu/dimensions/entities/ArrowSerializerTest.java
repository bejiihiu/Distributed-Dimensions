package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.Trident;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrowSerializerTest {

    private static ProxyEntities.Canned arrowCanned() {
        return new ProxyEntities.Canned("arrow")
                .answer("getPassengers", List.of())
                .answer("getDamage", 6.5)
                .answer("getPierceLevel", 2)
                .answer("isCritical", true)
                .answer("getPickupStatus", AbstractArrow.PickupStatus.DISALLOWED)
                .answer("getLifetimeTicks", 240);
    }

    @Test
    void ordinaryArrowReadCapturesCombatState() {
        Arrow arrow = ProxyEntities.entity(Arrow.class, EntityType.ARROW, arrowCanned());
        JsonObject fields = new ArrowSerializer().read(arrow, new EntityTransferMetrics());
        assertEquals(6.5, fields.get("damage").getAsDouble(), 0.0001);
        assertEquals(2, fields.get("pierceLevel").getAsInt());
        assertTrue(fields.get("critical").getAsBoolean());
        assertEquals("DISALLOWED", fields.get("pickupStatus").getAsString());
        assertEquals(240, fields.get("lifetimeTicks").getAsInt());
    }

    @Test
    void writeAppliesCombatState() {
        ProxyEntities.Canned out = arrowCanned();
        Arrow arrow = ProxyEntities.entity(Arrow.class, EntityType.ARROW, out);
        JsonObject fields = new JsonObject();
        fields.addProperty("damage", 9.25);
        fields.addProperty("pierceLevel", 4);
        fields.addProperty("critical", true);
        fields.addProperty("pickupStatus", "ALLOWED");
        fields.addProperty("lifetimeTicks", 500);
        new ArrowSerializer().write(arrow, fields, new EntityTransferMetrics());
        assertEquals(List.of(9.25), out.calls("setDamage").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(4), out.calls("setPierceLevel").stream().map(a -> ((Number) a).intValue()).toList());
        assertEquals(List.of(true), out.calls("setCritical"));
        assertEquals(List.of(AbstractArrow.PickupStatus.ALLOWED), out.calls("setPickupStatus"));
        assertEquals(List.of(500), out.calls("setLifetimeTicks").stream().map(a -> ((Number) a).intValue()).toList());
    }

    @Test
    void tridentRoundTripsLoyaltyAndGlint() {
        ProxyEntities.Canned out = new ProxyEntities.Canned("trident")
                .answer("getPassengers", List.of())
                .answer("getLoyaltyLevel", 3)
                .answer("hasGlint", true)
                .answer("hasDealtDamage", false)
                .answer("getDamage", 8.0)
                .answer("getPickupStatus", AbstractArrow.PickupStatus.DISALLOWED)
                .answer("getPierceLevel", 0)
                .answer("getLifetimeTicks", 100)
                .answer("isCritical", false);
        Trident trident = ProxyEntities.entity(Trident.class, EntityType.TRIDENT, out);
        JsonObject fields = new ArrowSerializer().read(trident, new EntityTransferMetrics());
        assertEquals(3, fields.get("loyaltyLevel").getAsInt());
        assertTrue(fields.get("tridentGlint").getAsBoolean());
        assertEquals(false, fields.get("tridentDealtDamage").getAsBoolean());

        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("loyaltyLevel", 2);
        writeFields.addProperty("tridentGlint", false);
        writeFields.addProperty("tridentDealtDamage", true);
        new ArrowSerializer().write(trident, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(2), out.calls("setLoyaltyLevel").stream().map(a -> ((Number) a).intValue()).toList());
        assertEquals(List.of(false), out.calls("setGlint"));
        assertEquals(List.of(true), out.calls("setHasDealtDamage"));
    }

    @Test
    void spectralArrowHasNoExtraFieldsButRoundTripsBaseState() {
        ProxyEntities.Canned out = arrowCanned();
        SpectralArrow arrow = ProxyEntities.entity(SpectralArrow.class, EntityType.SPECTRAL_ARROW, out);
        JsonObject fields = new ArrowSerializer().read(arrow, new EntityTransferMetrics());
        assertEquals(6.5, fields.get("damage").getAsDouble(), 0.0001);
    }

    @Test
    void invalidPickupStatusStringIsIgnoredNotFatal() {
        Arrow arrow = ProxyEntities.entity(Arrow.class, EntityType.ARROW, arrowCanned());
        JsonObject fields = new JsonObject();
        fields.addProperty("pickupStatus", "NOT_A_PICKUP_STATUS");
        EntityTransferMetrics metrics = new EntityTransferMetrics();
        new ArrowSerializer().write(arrow, fields, metrics);
        assertTrue(metrics.snapshot().getOrDefault(EntityTransferMetrics.DEGRADED, 0L) >= 1);
    }
}
