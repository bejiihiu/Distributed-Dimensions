package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecartSerializerTest {

    private static BlockData blockDataProxy(String asString) {
        return (BlockData) Proxy.newProxyInstance(MinecartSerializerTest.class.getClassLoader(),
                new Class<?>[] { BlockData.class },
                (proxy, method, args) -> method.getName().equals("getAsString") ? asString : null);
    }

    @Test
    void readCapturesMinecartState() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("minecart")
                .answer("getDamage", 12.5)
                .answer("getMaxSpeed", 0.9)
                .answer("isSlowWhenEmpty", true)
                .answer("getDerailedVelocityMod", new org.bukkit.util.Vector(0.1, 0.2, 0.3))
                .answer("getFlyingVelocityMod", new org.bukkit.util.Vector(0.5, 0.75, 1.0))
                .answer("getDisplayBlockData", blockDataProxy("minecraft:oak_planks[axis=y]"))
                .answer("getDisplayBlockOffset", 3)
                .answer("getPassengers", List.of());
        Minecart cart = ProxyEntities.entity(Minecart.class, EntityType.MINECART, canned);

        JsonObject fields = new MinecartSerializer().read(cart, new EntityTransferMetrics());
        assertEquals(12, fields.get("minecartDamage").getAsInt());
        assertEquals(0.9, fields.get("minecartMaxSpeed").getAsDouble(), 0.0001);
        assertTrue(fields.get("slowWhenEmpty").getAsBoolean());
        assertEquals(3, fields.get("displayBlockOffset").getAsInt());
        assertEquals("minecraft:oak_planks[axis=y]",
                fields.getAsJsonObject("displayBlock").get("data").getAsString());
        assertEquals(0.5, fields.getAsJsonObject("flyingVelocityMod").get("x").getAsDouble(), 0.0001);
    }

    @Test
    void writeAppliesMinecartState() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("minecart-out")
                .answer("getPassengers", List.of())
                .answer("getMaxSpeed", 0.4);
        Minecart cart = ProxyEntities.entity(Minecart.class, EntityType.MINECART, canned);

        JsonObject fields = new JsonObject();
        fields.addProperty("minecartDamage", 7);
        fields.addProperty("minecartMaxSpeed", 1.4);
        fields.addProperty("slowWhenEmpty", true);
        fields.addProperty("displayBlockOffset", 5);
        JsonObject display = new JsonObject();
        display.addProperty("data", (String) null);
        fields.add("displayBlock", display);

        new MinecartSerializer().write(cart, fields, new EntityTransferMetrics());
        assertEquals(List.of(7.0), canned.calls("setDamage").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(1.4), canned.calls("setMaxSpeed").stream().map(a -> ((Number) a).doubleValue()).toList());
        assertEquals(List.of(true), canned.calls("setSlowWhenEmpty"));
        assertEquals(List.of(5), canned.calls("setDisplayBlockOffset").stream().map(a -> ((Number) a).intValue()).toList());
    }

    @Test
    void chestMinecartCarriesInventoryContents() {
        Inventory inventory = (Inventory) Proxy.newProxyInstance(
                MinecartSerializerTest.class.getClassLoader(),
                new Class<?>[] { Inventory.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getContents" -> new ItemStack[5];
                    case "getSize" -> 5;
                    default -> null;
                });
        ProxyEntities.Canned canned = new ProxyEntities.Canned("storage-minecart")
                .answer("getPassengers", List.of())
                .answer("getInventory", inventory);
        StorageMinecart cart = ProxyEntities.entity(StorageMinecart.class, EntityType.CHEST_MINECART, canned);

        JsonObject fields = new MinecartSerializer().read(cart, new EntityTransferMetrics());
        assertTrue(fields.has("contents"));
        assertEquals(0, fields.getAsJsonArray("contents").size());
    }

    @Test
    void furnaceMinecartRoundTripsFuelTicks() {
        ProxyEntities.Canned canned = new ProxyEntities.Canned("furnace-minecart")
                .answer("getPassengers", List.of())
                .answer("getFuel", 1234)
                .answer("getPushX", -1.5)
                .answer("getPushZ", 2.5);
        org.bukkit.entity.minecart.PoweredMinecart cart = ProxyEntities.entity(
                org.bukkit.entity.minecart.PoweredMinecart.class, EntityType.FURNACE_MINECART, canned);

        JsonObject fields = new MinecartSerializer().read(cart, new EntityTransferMetrics());
        assertEquals(1234, fields.get("fuel").getAsInt());
        assertEquals(-1.5, fields.get("pushX").getAsDouble(), 0.0001);
        assertEquals(2.5, fields.get("pushZ").getAsDouble(), 0.0001);

        ProxyEntities.Canned out = new ProxyEntities.Canned("furnace-out")
                .answer("getPassengers", List.of())
                .answer("getFuel", 0);
        org.bukkit.entity.minecart.PoweredMinecart outCart = ProxyEntities.entity(
                org.bukkit.entity.minecart.PoweredMinecart.class, EntityType.FURNACE_MINECART, out);
        JsonObject writeFields = new JsonObject();
        writeFields.addProperty("fuel", 4321);
        writeFields.addProperty("pushX", 9.25);
        new MinecartSerializer().write(outCart, writeFields, new EntityTransferMetrics());
        assertEquals(List.of(4321), out.calls("setFuel").stream().map(a -> ((Number) a).intValue()).toList());
    }
}
