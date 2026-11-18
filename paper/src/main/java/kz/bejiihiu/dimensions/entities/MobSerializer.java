package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;

/**
 * Generic living entities — everything not covered by a dedicated serializer: mobs,
 * animals, golems, water creatures, villagers (type/profession/variant are carried by the
 * entity type key plus own-type fields we deliberately do not model). Base + living base
 * + (for {@link Mob}) persistence flags.
 *
 * <p>AI flags, memories, loot tables, raid state and variant NBT are not carried — custom
 * mobs from plugins degrade to a plain same-type mob (counted in
 * {@link EntityTransferMetrics#DEGRADED} when their type is outside the vanilla set).
 */
public final class MobSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "mob";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof LivingEntity;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        LivingEntity living = (LivingEntity) entity;
        JsonObject obj = readLivingNode(living, metrics);
        if (living instanceof Mob mob) {
            FieldCodec.putBoolean(obj, "aware", mob.isAware());
            FieldCodec.putBoolean(obj, "persistent", mob.isPersistenceRequired());
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        LivingEntity living = (LivingEntity) entity;
        writeLivingNode(living, fields, metrics);
        if (living instanceof Mob mob) {
            mob.setAware(FieldCodec.getBoolean(fields, "aware", true));
            mob.setPersistenceRequired(FieldCodec.getBoolean(fields, "persistent", false));
        }
    }
}
