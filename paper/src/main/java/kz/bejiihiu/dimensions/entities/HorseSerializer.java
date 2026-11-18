package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Llama;
import org.bukkit.inventory.AbstractHorseInventory;
import org.bukkit.inventory.HorseInventory;
import org.bukkit.inventory.LlamaInventory;

/**
 * Ridden horses, donkeys, mules and llamas — the whole abstract-horse family travels as
 * one kind, with sub-type details keyed off the entity type key. Taming state, ownership,
 * jump strength and the saddle travel for all of them; color/markings/armor travel for
 * {@link Horse}; strength/decor for {@link Llama} (and {@link TraderLlama}); chest contents
 * for chested {@link ChestedHorse}s (donkeys, mules, llamas); zombie/skeleton horses get
 * taming state plus the saddle.
 *
 * <p>{@code domestication} travels so a half-tame horse keeps its progress. Undead/skeleton
 * horse color/markings/armor do not exist in vanilla and are never written.
 */
public final class HorseSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "horse";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof AbstractHorse;
    }

    @Override
    public kz.bejiihiu.dimensions.common.model.EntityPayloadKind kindOf(Entity entity) {
        AbstractHorse horse = (AbstractHorse) entity;
        if (horse instanceof Horse) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.HORSE;
        }
        if (horse instanceof org.bukkit.entity.Donkey) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.DONKEY;
        }
        if (horse instanceof org.bukkit.entity.Mule) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MULE;
        }
        if (horse instanceof Llama) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.LLAMA;
        }
        if (horse instanceof org.bukkit.entity.ZombieHorse) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.ZOMBIE_HORSE;
        }
        if (horse instanceof org.bukkit.entity.SkeletonHorse) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.SKELETON_HORSE;
        }
        return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MOB;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        AbstractHorse horse = (AbstractHorse) entity;
        JsonObject obj = readLivingNode(horse, metrics);
        FieldCodec.putDouble(obj, "jumpStrength", horse.getJumpStrength());
        FieldCodec.putInt(obj, "domestication", horse.getDomestication());
        FieldCodec.putInt(obj, "maxDomestication", horse.getMaxDomestication());
        FieldCodec.putBoolean(obj, "tamed", horse.isTamed());
        if (horse.getOwnerUniqueId() != null) {
            obj.addProperty("owner", horse.getOwnerUniqueId().toString());
        }
        AbstractHorseInventory inventory = horse.getInventory();
        if (inventory != null) {
            JsonObject saddle = FieldCodec.item(inventory.getSaddle());
            if (saddle != null) {
                obj.add("saddle", saddle);
            }
        }
        if (horse instanceof Horse regular) {
            obj.addProperty("horseColor", regular.getColor().name());
            obj.addProperty("horseStyle", regular.getStyle().name());
            HorseInventory horseInventory = regular.getInventory();
            JsonObject armor = FieldCodec.item(horseInventory.getArmor());
            if (armor != null) {
                obj.add("armor", armor);
            }
        }
        if (horse instanceof ChestedHorse chested) {
            FieldCodec.putBoolean(obj, "carryingChest", chested.isCarryingChest());
            if (chested.isCarryingChest() && inventory != null) {
                obj.add("contents", FieldCodec.slots(inventory.getStorageContents()));
            }
        }
        if (horse instanceof Llama llama) {
            obj.addProperty("llamaColor", llama.getColor().name());
            FieldCodec.putInt(obj, "strength", llama.getStrength());
            LlamaInventory llamaInventory = llama.getInventory();
            JsonObject decor = llamaInventory == null ? null : FieldCodec.item(llamaInventory.getDecor());
            if (decor != null) {
                obj.add("decor", decor);
            }
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        AbstractHorse horse = (AbstractHorse) entity;
        writeLivingNode(horse, fields, metrics);
        horse.setJumpStrength(FieldCodec.getDouble(fields, "jumpStrength", horse.getJumpStrength()));
        horse.setDomestication(FieldCodec.getInt(fields, "domestication", horse.getDomestication()));
        horse.setMaxDomestication(FieldCodec.getInt(fields, "maxDomestication", horse.getMaxDomestication()));
        horse.setTamed(FieldCodec.getBoolean(fields, "tamed", false));
        java.util.UUID owner = FieldCodec.uuid(fields, "owner");
        if (owner != null) {
            // UUID-based OfflinePlayer lookup does not block-load the profile; ownership in
            // vanilla is a UUID reference, so this restores loyalty exactly
            horse.setOwner(org.bukkit.Bukkit.getOfflinePlayer(owner));
        }
        AbstractHorseInventory inventory = horse.getInventory();
        if (inventory != null && fields.has("saddle")) {
            inventory.setSaddle(FieldCodec.nullableItem(fields.getAsJsonObject("saddle")));
        }
        if (horse instanceof Horse regular) {
            String color = FieldCodec.getString(fields, "horseColor");
            if (color != null) {
                try {
                    regular.setColor(Horse.Color.valueOf(color));
                } catch (IllegalArgumentException e) {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            String style = FieldCodec.getString(fields, "horseStyle");
            if (style != null) {
                try {
                    regular.setStyle(Horse.Style.valueOf(style));
                } catch (IllegalArgumentException e) {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            if (fields.has("armor")) {
                HorseInventory horseInventory = regular.getInventory();
                horseInventory.setArmor(FieldCodec.nullableItem(fields.getAsJsonObject("armor")));
            }
        }
        if (horse instanceof ChestedHorse chested) {
            boolean carryingChest = FieldCodec.getBoolean(fields, "carryingChest", false);
            chested.setCarryingChest(carryingChest);
            if (carryingChest && inventory != null && fields.has("contents")) {
                org.bukkit.inventory.ItemStack[] contents =
                        FieldCodec.slots(fields.getAsJsonArray("contents"), inventory.getStorageContents().length);
                inventory.setStorageContents(contents);
            }
        }
        if (horse instanceof Llama llama) {
            String color = FieldCodec.getString(fields, "llamaColor");
            if (color != null) {
                try {
                    llama.setColor(Llama.Color.valueOf(color));
                } catch (IllegalArgumentException e) {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            llama.setStrength(Math.max(1, Math.min(5, FieldCodec.getInt(fields, "strength", llama.getStrength()))));
            if (fields.has("decor")) {
                llama.getInventory().setDecor(FieldCodec.nullableItem(fields.getAsJsonObject("decor")));
            }
        }
    }

}
