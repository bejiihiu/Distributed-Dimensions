package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Entity;

/**
 * The floor every unknown entity lands in: type key, health (when living), custom name and
 * the shared base flags. Anything carried here is {@code unknown} to the serializer layer,
 * which increments {@link EntityTransferMetrics#DEGRADED} once per entity — never per
 * missing field — so operators can grep the log instead of diffing inventories.
 *
 * <p>Used for entities outside the vanilla whitelist (plugin-owned entities). Wither,
 * ender dragon and creaking never reach this serializer — they are excluded earlier in
 * the pipeline — but as a safety net they degrade instead of crashing.
 */
public final class DegradedSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "unknown_degraded";
    }

    @Override
    public boolean supports(Entity entity) {
        return true;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        JsonObject obj = readBase(entity);
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            FieldCodec.putDouble(obj, "health", living.getHealth());
            FieldCodec.putDouble(obj, "maxHealth", living.getMaxHealth());
        }
        metrics.increment(EntityTransferMetrics.DEGRADED);
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        writeBase(entity, fields, metrics);
        if (entity instanceof org.bukkit.entity.LivingEntity living) {
            double maxHealth = FieldCodec.getDouble(fields, "maxHealth", living.getMaxHealth());
            if (maxHealth > 0 && maxHealth != living.getMaxHealth()) {
                try {
                    living.setMaxHealth(maxHealth);
                } catch (RuntimeException ignored) {
                    // not all living entities accept max-health changes
                }
            }
            double health = FieldCodec.getDouble(fields, "health", living.getMaxHealth());
            living.setHealth(Math.min(Math.max(health, 0.0), living.getMaxHealth()));
        }
    }
}
