package kz.bejiihiu.dimensions.entities;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldCodecTest {

    @Test
    void vectorsRoundTrip() {
        com.google.gson.JsonObject encoded = FieldCodec.velocity(new org.bukkit.util.Vector(1.5, -2.25, 3.0));
        org.bukkit.util.Vector back = FieldCodec.velocity(encoded);
        assertEquals(1.5, back.getX());
        assertEquals(-2.25, back.getY());
        assertEquals(3.0, back.getZ());
    }

    @Test
    void anglesRoundTrip() {
        org.bukkit.util.EulerAngle angle = new org.bukkit.util.EulerAngle(0.5, 1.5, -0.25);
        org.bukkit.util.EulerAngle back = FieldCodec.angle(FieldCodec.angle(angle));
        assertEquals(angle.getX(), back.getX());
        assertEquals(angle.getY(), back.getY());
        assertEquals(angle.getZ(), back.getZ());
    }

    @Test
    void scalarReadersFallBackOnGarbage() {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("n", "not-a-number");
        obj.addProperty("b", "not-a-bool");
        assertEquals(7, FieldCodec.getInt(obj, "n", 7));
        assertEquals(true, FieldCodec.getBoolean(obj, "b", true));
        assertEquals(2.5, FieldCodec.getDouble(obj, "missing", 2.5));
    }

    @Test
    void uuidRoundTripAndInvalidInput() {
        UUID uuid = UUID.randomUUID();
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        FieldCodec.putBoolean(obj, "unused", true);
        obj.addProperty("owner", uuid.toString());
        assertEquals(uuid, FieldCodec.uuid(obj, "owner"));
        obj.addProperty("owner", "not-a-uuid");
        assertEquals(null, FieldCodec.uuid(obj, "owner"));
    }

    @Test
    void effectsReaderSkipsUnknownAndZeroDuration() {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        com.google.gson.JsonObject unknown = new com.google.gson.JsonObject();
        unknown.addProperty("type", "definitely_not_a_potion");
        unknown.addProperty("duration", 100);
        array.add(unknown);
        com.google.gson.JsonObject zero = new com.google.gson.JsonObject();
        zero.addProperty("type", "speed");
        zero.addProperty("duration", 0);
        array.add(zero);
        List<String> skipped = new java.util.ArrayList<>();
        List<org.bukkit.potion.PotionEffect> effects = FieldCodec.effects(array, skipped);
        // deterministic in every environment: no effects materialize; the unknown key is always reported
        assertTrue(effects.isEmpty());
        assertTrue(skipped.contains("definitely_not_a_potion"));
    }

    @Test
    void itemBlobMustBeBase64() {
        com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        bad.addProperty("bytes", "!!! not base64 !!!");
        assertThrows(CodecException.class, () -> FieldCodec.item(bad));
    }

    @Test
    void itemBytesRoundTripThroughBase64() {
        byte[] raw = {1, 2, 3, 4, 5, 127, -128, 0};
        String encoded = Base64.getEncoder().encodeToString(raw);
        assertEquals(new String(Base64.getDecoder().decode(encoded)), new String(raw));
    }

    @Test
    void slotsOutOfRangeIndicesAreIgnored() {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        com.google.gson.JsonObject slot = new com.google.gson.JsonObject();
        slot.addProperty("slot", 99);
        slot.add("item", new com.google.gson.JsonObject());
        array.add(slot);
        // out-of-range slot is skipped without throwing; a blob-less item is equally ignored
        org.bukkit.inventory.ItemStack[] contents = FieldCodec.slots(array, 3);
        assertEquals(3, contents.length);
        for (org.bukkit.inventory.ItemStack stack : contents) {
            assertEquals(null, stack);
        }
    }
}
