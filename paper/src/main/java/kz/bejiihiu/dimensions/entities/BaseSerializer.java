package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Field fragments shared by every serializer: flags every entity carries, the living-entity
 * base, and the equipment set. Kept in one place so the base read/write pair cannot drift
 * across kinds.
 */
abstract class BaseSerializer implements EntitySerializer {

    // ------------------------------------------------------------------ base (every entity)

    protected static JsonObject readBase(Entity entity) {
        JsonObject obj = new JsonObject();
        FieldCodec.putDouble(obj, "yaw", entity.getYaw());
        FieldCodec.putDouble(obj, "pitch", entity.getPitch());
        org.bukkit.util.Vector velocity = entity.getVelocity();
        obj.add("velocity", FieldCodec.velocity(velocity == null ? new org.bukkit.util.Vector(0, 0, 0) : velocity));
        FieldCodec.putInt(obj, "fireTicks", entity.getFireTicks());
        FieldCodec.putInt(obj, "freezeTicks", entity.getFreezeTicks());
        FieldCodec.putBoolean(obj, "silent", entity.isSilent());
        FieldCodec.putBoolean(obj, "glowing", entity.isGlowing());
        FieldCodec.putBoolean(obj, "invisible", entity.isInvisible());
        // pairs with writeBase()'s setInvulnerable: the marked state round-trips, temporary ticks don't
        FieldCodec.putBoolean(obj, "invulnerable", entity.isMarkedInvulnerable());
        String customName = FieldCodec.name(entity.customName());
        if (customName != null) {
            obj.addProperty("customName", customName);
        }
        FieldCodec.putBoolean(obj, "customNameVisible", entity.isCustomNameVisible());
        return obj;
    }

    protected static void writeBase(Entity entity, JsonObject obj, EntityTransferMetrics metrics) {
        double yaw = FieldCodec.getDouble(obj, "yaw", entity.getYaw());
        double pitch = FieldCodec.getDouble(obj, "pitch", entity.getPitch());
        entity.setRotation((float) yaw, (float) pitch);
        entity.setVelocity(FieldCodec.velocity(obj.getAsJsonObject("velocity")));
        entity.setFireTicks(Math.max(0, FieldCodec.getInt(obj, "fireTicks", 0)));
        entity.setFreezeTicks(Math.max(0, FieldCodec.getInt(obj, "freezeTicks", 0)));
        entity.setSilent(FieldCodec.getBoolean(obj, "silent", false));
        entity.setGlowing(FieldCodec.getBoolean(obj, "glowing", false));
        entity.setInvisible(FieldCodec.getBoolean(obj, "invisible", false));
        entity.setInvulnerable(FieldCodec.getBoolean(obj, "invulnerable", false));
        Component name = FieldCodec.name(obj, "customName");
        if (name != null || obj.has("customName")) {
            entity.customName(name);
            entity.setCustomNameVisible(FieldCodec.getBoolean(obj, "customNameVisible", name != null));
        }
        // fall distance intentionally not restored — landing fresh is the safe default
    }

    // ------------------------------------------------------------------ living base

    protected static JsonObject readLiving(LivingEntity entity, JsonObject obj, EntityTransferMetrics metrics) {
        FieldCodec.putDouble(obj, "health", entity.getHealth());
        FieldCodec.putDouble(obj, "maxHealth", entity.getMaxHealth());
        if (entity instanceof Ageable ageable) {
            FieldCodec.putInt(obj, "age", ageable.getAge());
        }
        java.util.Collection<org.bukkit.potion.PotionEffect> effects = entity.getActivePotionEffects();
        obj.add("effects", FieldCodec.effects(effects == null ? List.of() : effects));
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) {
            obj.add("equipment", FieldCodec.equipment(
                    equipment.getItemInMainHand(),
                    equipment.getItemInOffHand(),
                    equipment.getHelmet(),
                    equipment.getChestplate(),
                    equipment.getLeggings(),
                    equipment.getBoots()));
        }
        return obj;
    }

    protected static void writeLiving(LivingEntity entity, JsonObject obj, EntityTransferMetrics metrics) {
        // max health first — bukkit rejects health above max
        double maxHealth = FieldCodec.getDouble(obj, "maxHealth", entity.getMaxHealth());
        if (maxHealth > 0 && maxHealth != entity.getMaxHealth()) {
            try {
                entity.setMaxHealth(maxHealth);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                metrics.increment(EntityTransferMetrics.DEGRADED);
            }
        }
        double health = FieldCodec.getDouble(obj, "health", entity.getMaxHealth());
        entity.setHealth(Math.min(Math.max(health, 0.0), entity.getMaxHealth()));
        if (entity instanceof Ageable ageable
                && obj.has("age") && obj.get("age").isJsonPrimitive() && obj.get("age").getAsJsonPrimitive().isNumber()) {
            ageable.setAge(obj.get("age").getAsInt());
        }
        java.util.List<String> skipped = new java.util.ArrayList<>();
        for (org.bukkit.potion.PotionEffect effect : FieldCodec.effects(
                obj.getAsJsonArray("effects"), skipped)) {
            entity.addPotionEffect(effect);
        }
        if (!skipped.isEmpty()) {
            metrics.add(EntityTransferMetrics.DEGRADED, skipped.size());
        }
        JsonObject equipment = obj.getAsJsonObject("equipment");
        if (equipment != null) {
            FieldCodec.EquipmentTriple set = FieldCodec.equipment(equipment);
            EntityEquipment target = entity.getEquipment();
            if (target != null) {
                target.setItemInMainHand(set.mainHand() == null ? new ItemStack(org.bukkit.Material.AIR) : set.mainHand());
                target.setItemInOffHand(set.offHand() == null ? new ItemStack(org.bukkit.Material.AIR) : set.offHand());
                target.setHelmet(set.helmet() == null ? new ItemStack(org.bukkit.Material.AIR) : set.helmet());
                target.setChestplate(set.chestplate() == null ? new ItemStack(org.bukkit.Material.AIR) : set.chestplate());
                target.setLeggings(set.leggings() == null ? new ItemStack(org.bukkit.Material.AIR) : set.leggings());
                target.setBoots(set.boots() == null ? new ItemStack(org.bukkit.Material.AIR) : set.boots());
            }
        }
    }

    /** Shared writer entry: base then living — used by living-kind serializers. */
    protected static void writeLivingNode(LivingEntity entity, JsonObject obj, EntityTransferMetrics metrics) {
        writeBase(entity, obj, metrics);
        writeLiving(entity, obj, metrics);
    }

    /** Shared reader entry: base then living. */
    protected static JsonObject readLivingNode(LivingEntity entity, EntityTransferMetrics metrics) {
        JsonObject obj = readBase(entity);
        return readLiving(entity, obj, metrics);
    }

    /** Human-player passenger guard used by the assembly walker. */
    protected static boolean isPassengerRecordable(org.bukkit.entity.Entity passenger) {
        return !(passenger instanceof HumanEntity);
    }
}
