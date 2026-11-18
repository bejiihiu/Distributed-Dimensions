package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;

/**
 * Boats — including chest boats (their contents travel in the same {@code contents} field as
 * minecarts). The boat's wood type and chest/non-chest variant are carried by the entity
 * type key (e.g. {@code minecraft:oak_chest_boat}), so no extra fields are needed; the
 * deprecated deceleration/max-speed knobs are not serialized (their own javadoc says they
 * do not work correctly across versions).
 */
public final class BoatSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "boat";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof Boat;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        Boat boat = (Boat) entity;
        JsonObject obj = readBase(entity);
        if (boat instanceof InventoryHolder holder) {
            obj.add("contents", FieldCodec.slots(holder.getInventory().getContents()));
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        Boat boat = (Boat) entity;
        writeBase(boat, fields, metrics);
        if (boat instanceof InventoryHolder holder && fields.has("contents")) {
            org.bukkit.inventory.ItemStack[] contents =
                    FieldCodec.slots(fields.getAsJsonArray("contents"), holder.getInventory().getSize());
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] != null) {
                    holder.getInventory().setItem(i, contents[i]);
                }
            }
        }
        if (boat instanceof ChestBoat) {
            // nothing extra to wire — the spawn consumer already runs pre-attach
        }
    }
}
