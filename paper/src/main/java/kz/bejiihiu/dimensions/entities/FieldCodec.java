package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * JSON field codec for entity payloads — positions, vectors, items, effects, names and
 * flags. Byte arrays (item NBT blobs) always travel as explicit Base64 strings; Gson never
 * sees a raw {@code byte[]}.
 *
 * <p>All readers are lenient where the data is still meaningful: a malformed item blob is
 * a hard failure (the op is abandoned, nothing is half-spawned); a malformed boolean is a
 * default. The strict part is the structural decode in {@link EntityCodec}.
 */
public final class FieldCodec {

    private FieldCodec() {
    }

    // ------------------------------------------------------------------ scalars

    public static double getDouble(JsonObject obj, String key, double fallback) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsDouble() : fallback;
    }

    public static int getInt(JsonObject obj, String key, int fallback) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? e.getAsInt() : fallback;
    }

    public static boolean getBoolean(JsonObject obj, String key, boolean fallback) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()
                ? e.getAsBoolean() : fallback;
    }

    public static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()
                ? e.getAsString() : null;
    }

    public static void putDouble(JsonObject obj, String key, double value) {
        obj.addProperty(key, value);
    }

    public static void putInt(JsonObject obj, String key, int value) {
        obj.addProperty(key, value);
    }

    public static void putBoolean(JsonObject obj, String key, boolean value) {
        obj.addProperty(key, value);
    }

    // ------------------------------------------------------------------ vectors & angles

    public static JsonObject velocity(Vector v) {
        if (v == null) {
            v = new Vector(0, 0, 0);
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("x", v.getX());
        obj.addProperty("y", v.getY());
        obj.addProperty("z", v.getZ());
        return obj;
    }

    public static Vector velocity(JsonObject obj) {
        if (obj == null) {
            return new Vector(0, 0, 0);
        }
        return new Vector(getDouble(obj, "x", 0.0), getDouble(obj, "y", 0.0), getDouble(obj, "z", 0.0));
    }

    public static JsonObject angle(EulerAngle angle) {
        if (angle == null) {
            angle = EulerAngle.ZERO;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("x", angle.getX());
        obj.addProperty("y", angle.getY());
        obj.addProperty("z", angle.getZ());
        return obj;
    }

    public static EulerAngle angle(JsonObject obj) {
        if (obj == null) {
            return new EulerAngle(0, 0, 0);
        }
        return new EulerAngle(getDouble(obj, "x", 0.0), getDouble(obj, "y", 0.0), getDouble(obj, "z", 0.0));
    }

    // ------------------------------------------------------------------ items (NBT bytes, Base64)

    /** Serialize an item stack to a {@code {bytes: base64}} object; empty stack → null. */
    public static JsonObject item(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
            return null;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("bytes", Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        return obj;
    }

    /** Read back a stack. A blob that cannot be deserialized is a hard failure. */
    public static ItemStack item(JsonObject obj) {
        String base64 = getString(obj, "bytes");
        if (base64 == null) {
            throw new CodecException("item blob missing 'bytes'");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new CodecException("item blob is not valid Base64", e);
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (RuntimeException e) {
            throw new CodecException("item blob could not be deserialized: " + e.getMessage(), e);
        }
    }

    /** Read a stack that is allowed to be absent. */
    public static ItemStack nullableItem(JsonObject obj) {
        if (obj == null || !obj.has("bytes") || obj.get("bytes").isJsonNull()) {
            return null;
        }
        return item(obj);
    }

    /** Slot-indexed items from an inventory: {@code [{slot, item:{bytes}}]}. */
    public static JsonArray slots(ItemStack[] contents) {
        JsonArray array = new JsonArray();
        if (contents == null) {
            return array;
        }
        for (int i = 0; i < contents.length; i++) {
            JsonObject item = item(contents[i]);
            if (item == null) {
                continue;
            }
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", i);
            slot.add("item", item);
            array.add(slot);
        }
        return array;
    }

    public static ItemStack[] slots(JsonArray array, int inventorySize) {
        ItemStack[] contents = new ItemStack[Math.max(0, inventorySize)];
        if (array == null) {
            return contents;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject slot = element.getAsJsonObject();
            int index = getInt(slot, "slot", -1);
            if (index < 0 || index >= contents.length) {
                continue;   // slot drifted across versions — skip, never crash
            }
            JsonObject item = slot.getAsJsonObject("item");
            if (item == null) {
                continue;
            }
            ItemStack stack = nullableItem(item);
            if (stack != null) {
                contents[index] = stack;
            }
        }
        return contents;
    }

    /** The full equipment set as a JSON object of slot → item (may contain nulls). */
    public static JsonObject equipment(ItemStack mainHand, ItemStack offHand,
            ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
        JsonObject obj = new JsonObject();
        obj.add("mainHand", itemOrNullElement(mainHand));
        obj.add("offHand", itemOrNullElement(offHand));
        obj.add("helmet", itemOrNullElement(helmet));
        obj.add("chestplate", itemOrNullElement(chestplate));
        obj.add("leggings", itemOrNullElement(leggings));
        obj.add("boots", itemOrNullElement(boots));
        return obj;
    }

    private static JsonElement itemOrNullElement(ItemStack stack) {
        JsonObject item = item(stack);
        return item == null ? com.google.gson.JsonNull.INSTANCE : item;
    }

    public static EquipmentTriple equipment(JsonObject obj) {
        return new EquipmentTriple(
                nullableItem(obj == null ? null : obj.getAsJsonObject("mainHand")),
                nullableItem(obj == null ? null : obj.getAsJsonObject("offHand")),
                nullableItem(obj == null ? null : obj.getAsJsonObject("helmet")),
                nullableItem(obj == null ? null : obj.getAsJsonObject("chestplate")),
                nullableItem(obj == null ? null : obj.getAsJsonObject("leggings")),
                nullableItem(obj == null ? null : obj.getAsJsonObject("boots")));
    }

    /** Parsed equipment, hands + armor. */
    public record EquipmentTriple(ItemStack mainHand, ItemStack offHand,
            ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots) {
    }

    // ------------------------------------------------------------------ potion effects

    public static JsonObject effect(PotionEffect effect) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", effect.getType().getKey().getKey());
        obj.addProperty("duration", effect.getDuration());
        obj.addProperty("amplifier", effect.getAmplifier());
        obj.addProperty("ambient", effect.isAmbient());
        obj.addProperty("particles", effect.hasParticles());
        obj.addProperty("icon", effect.hasIcon());
        return obj;
    }

    public static JsonArray effects(Iterable<PotionEffect> effects) {
        JsonArray array = new JsonArray();
        for (PotionEffect effect : effects) {
            array.add(effect(effect));
        }
        return array;
    }

    /** Potion-type lookup that never throws — a registry hiccup degrades, never crashes the op. */
    public static PotionEffectType potionByKey(String key) {
        try {
            org.bukkit.Registry<PotionEffectType> registry = org.bukkit.Registry.MOB_EFFECT;
            return registry == null ? null : registry.get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** PotionType (arrow base potion) lookup that never throws. */
    public static org.bukkit.potion.PotionType potionTypeByKey(String key) {
        try {
            org.bukkit.Registry<org.bukkit.potion.PotionType> registry = org.bukkit.Registry.POTION;
            return registry == null ? null : registry.get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return the effects, skipping entries whose type no longer exists (logged by callers if needed). */
    public static List<PotionEffect> effects(JsonArray array, List<String> skippedTypes) {
        List<PotionEffect> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String typeKey = getString(obj, "type");
            PotionEffectType type = typeKey == null ? null : potionByKey(typeKey);
            if (type == null) {
                if (skippedTypes != null && typeKey != null) {
                    skippedTypes.add(typeKey);
                }
                continue;
            }
            int duration = getInt(obj, "duration", 0);
            if (duration != PotionEffect.INFINITE_DURATION && duration <= 0) {
                continue;
            }
            result.add(new PotionEffect(type, duration,
                    getInt(obj, "amplifier", 0),
                    getBoolean(obj, "ambient", false),
                    getBoolean(obj, "particles", true),
                    getBoolean(obj, "icon", true)));
        }
        return result;
    }

    // ------------------------------------------------------------------ names (MiniMessage)

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Serialize a custom name to MiniMessage text; {@code null} name → JSON null. */
    public static String name(Component name) {
        return name == null ? null : MINI.serialize(name);
    }

    public static Component name(JsonObject obj, String key) {
        String text = getString(obj, key);
        if (text == null) {
            return null;
        }
        try {
            return MINI.deserialize(text);
        } catch (RuntimeException e) {
            // a name that no longer parses should not sink the entity — drop it, loudly
            return null;
        }
    }

    // ------------------------------------------------------------------ misc

    public static JsonObject uuid(UUID uuid) {
        JsonObject obj = new JsonObject();
        obj.addProperty("uuid", uuid == null ? null : uuid.toString());
        return obj;
    }

    public static UUID uuid(JsonObject obj, String key) {
        String value = getString(obj, key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static JsonObject blockData(BlockData data) {
        JsonObject obj = new JsonObject();
        obj.addProperty("data", data == null ? null : data.getAsString());
        return obj;
    }

    /** Parse a block-data string; {@code null} string → null. Unknown data is a hard failure. */
    public static BlockData blockData(JsonObject obj) {
        String value = getString(obj, "data");
        if (value == null) {
            return null;
        }
        try {
            return org.bukkit.Bukkit.createBlockData(value);
        } catch (IllegalArgumentException e) {
            throw new CodecException("block data '" + value + "' is not parseable: " + e.getMessage(), e);
        }
    }

    public static JsonObject color(Color color) {
        if (color == null) {
            return null;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("rgb", color.asRGB());
        return obj;
    }

    public static Color color(JsonObject obj) {
        if (obj == null) {
            return null;
        }
        JsonElement e = obj.get("rgb");
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()
                ? Color.fromRGB(e.getAsInt() & 0xFFFFFF) : null;
    }

    public static JsonArray uuidArray(List<UUID> uuids) {
        JsonArray array = new JsonArray();
        for (UUID uuid : uuids) {
            array.add(new JsonPrimitive(uuid.toString()));
        }
        return array;
    }
}
