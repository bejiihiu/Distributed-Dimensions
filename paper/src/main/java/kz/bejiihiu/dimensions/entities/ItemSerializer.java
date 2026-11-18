package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

/** Dropped items — full stack NBT plus the pickup-related state. */
public final class ItemSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "item";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof Item;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        Item item = (Item) entity;
        JsonObject obj = readBase(entity);
        JsonObject stack = FieldCodec.item(item.getItemStack());
        if (stack != null) {
            obj.add("item", stack);
        }
        FieldCodec.putInt(obj, "pickupDelay", item.getPickupDelay());
        FieldCodec.putBoolean(obj, "unlimitedLifetime", item.isUnlimitedLifetime());
        FieldCodec.putBoolean(obj, "canMobPickup", item.canMobPickup());
        FieldCodec.putBoolean(obj, "canPlayerPickup", item.canPlayerPickup());
        FieldCodec.putBoolean(obj, "willAge", item.willAge());
        FieldCodec.putInt(obj, "health", item.getHealth());
        String owner = item.getOwner() == null ? null : item.getOwner().toString();
        if (owner != null) {
            obj.addProperty("owner", owner);
        }
        String thrower = item.getThrower() == null ? null : item.getThrower().toString();
        if (thrower != null) {
            obj.addProperty("thrower", thrower);
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        Item item = (Item) entity;
        writeBase(item, fields, metrics);
        JsonObject stack = fields.getAsJsonObject("item");
        if (stack != null) {
            item.setItemStack(FieldCodec.item(stack));
        }
        item.setPickupDelay(FieldCodec.getInt(fields, "pickupDelay", 0));
        item.setUnlimitedLifetime(FieldCodec.getBoolean(fields, "unlimitedLifetime", false));
        item.setCanMobPickup(FieldCodec.getBoolean(fields, "canMobPickup", true));
        item.setCanPlayerPickup(FieldCodec.getBoolean(fields, "canPlayerPickup", true));
        item.setWillAge(FieldCodec.getBoolean(fields, "willAge", true));
        // health intentionally restored only when it fits the default max (5)
        int health = FieldCodec.getInt(fields, "health", item.getHealth());
        item.setHealth(Math.max(1, health));
        if (fields.has("owner")) {
            item.setOwner(FieldCodec.uuid(fields, "owner"));
        }
        if (fields.has("thrower")) {
            item.setThrower(FieldCodec.uuid(fields, "thrower"));
        }
    }
}
