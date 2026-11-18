package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.Color;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.Trident;
import org.bukkit.potion.PotionEffect;

/**
 * Thrown arrows — including spectral arrows, tipped arrows and tridents (all are
 * {@link AbstractArrow}). Item NBT travels in the item blob; potion payloads travel as
 * explicit potion-effect arrays.
 */
public final class ArrowSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "arrow";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof AbstractArrow;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        AbstractArrow arrow = (AbstractArrow) entity;
        JsonObject obj = readBase(entity);
        FieldCodec.putDouble(obj, "damage", arrow.getDamage());
        FieldCodec.putInt(obj, "pierceLevel", arrow.getPierceLevel());
        FieldCodec.putBoolean(obj, "critical", arrow.isCritical());
        obj.addProperty("pickupStatus", arrow.getPickupStatus().name());
        FieldCodec.putInt(obj, "lifetimeTicks", arrow.getLifetimeTicks());
        JsonObject item = FieldCodec.item(arrow.getItemStack());
        if (item != null) {
            obj.add("item", item);
        }
        JsonObject weapon = FieldCodec.item(arrow.getWeapon());
        if (weapon != null) {
            obj.add("weapon", weapon);
        }
        if (arrow instanceof Arrow tipped) {
            if (tipped.getBasePotionType() != null) {
                obj.addProperty("basePotionType", tipped.getBasePotionType().getKey().getKey());
            }
            Color color = tipped.getColor();
            if (color != null) {
                obj.addProperty("basePotionColor", color.asRGB());
            }
            if (tipped.hasCustomEffects()) {
                obj.add("customEffects", FieldCodec.effects(tipped.getCustomEffects()));
            }
        }
        if (arrow instanceof Trident trident) {
            FieldCodec.putInt(obj, "loyaltyLevel", trident.getLoyaltyLevel());
            FieldCodec.putBoolean(obj, "tridentGlint", trident.hasGlint());
            FieldCodec.putBoolean(obj, "tridentDealtDamage", trident.hasDealtDamage());
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        AbstractArrow arrow = (AbstractArrow) entity;
        writeBase(arrow, fields, metrics);
        arrow.setDamage(FieldCodec.getDouble(fields, "damage", arrow.getDamage()));
        arrow.setPierceLevel(Math.max(0, Math.min(127, FieldCodec.getInt(fields, "pierceLevel", 0))));
        arrow.setCritical(FieldCodec.getBoolean(fields, "critical", false));
        String status = FieldCodec.getString(fields, "pickupStatus");
        if (status != null) {
            try {
                arrow.setPickupStatus(AbstractArrow.PickupStatus.valueOf(status));
            } catch (IllegalArgumentException e) {
                metrics.increment(EntityTransferMetrics.DEGRADED);
            }
        }
        arrow.setLifetimeTicks(Math.max(0, FieldCodec.getInt(fields, "lifetimeTicks", 0)));
        JsonObject item = fields.getAsJsonObject("item");
        if (item != null) {
            arrow.setItemStack(FieldCodec.item(item));
        }
        JsonObject weapon = fields.getAsJsonObject("weapon");
        if (weapon != null) {
            try {
                arrow.setWeapon(FieldCodec.item(weapon));
            } catch (RuntimeException e) {
                metrics.increment(EntityTransferMetrics.DEGRADED);
            }
        }
        if (arrow instanceof Arrow tipped) {
            String baseType = FieldCodec.getString(fields, "basePotionType");
            if (baseType != null) {
                org.bukkit.potion.PotionType base = FieldCodec.potionTypeByKey(baseType);
                if (base != null) {
                    tipped.setBasePotionType(base);
                } else {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            if (fields.has("basePotionColor") && fields.get("basePotionColor").isJsonPrimitive()) {
                tipped.setColor(Color.fromRGB(fields.get("basePotionColor").getAsInt() & 0xFFFFFF));
            }
            JsonObject effectsContainer = fields;
            if (effectsContainer.has("customEffects")) {
                java.util.List<String> skipped = new java.util.ArrayList<>();
                for (PotionEffect effect : FieldCodec.effects(
                        effectsContainer.getAsJsonArray("customEffects"), skipped)) {
                    tipped.addCustomEffect(effect, true);
                }
                if (!skipped.isEmpty()) {
                    metrics.add(EntityTransferMetrics.DEGRADED, skipped.size());
                }
            }
        }
        if (arrow instanceof Trident trident) {
            trident.setLoyaltyLevel(Math.max(0, Math.min(127, FieldCodec.getInt(fields, "loyaltyLevel", 0))));
            trident.setGlint(FieldCodec.getBoolean(fields, "tridentGlint", false));
            trident.setHasDealtDamage(FieldCodec.getBoolean(fields, "tridentDealtDamage", false));
        }
    }
}
