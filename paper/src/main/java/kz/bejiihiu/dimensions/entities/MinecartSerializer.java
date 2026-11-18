package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonObject;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.PoweredMinecart;
import org.bukkit.entity.minecart.SpawnerMinecart;
import org.bukkit.entity.Minecart;

/**
 * Minecarts — one serializer covering all variants. The variant is carried by the entity
 * type key ({@code minecraft:chest_minecart} …); variant-specific state travels as optional
 * fields:
 *
 * <ul>
 *   <li>{@code contents} — slot-indexed inventory (any minecart backed by an
 *       {@link InventoryHolder}: storage, hopper, command keeps command state instead)</li>
 *   <li>{@code hopperEnabled} — hopper minecarts</li>
 *   <li>{@code fuel} / {@code pushX} / {@code pushZ} — furnace (powered) minecarts. Note the
 *       burn progress the vanilla furnace GUI shows has no public API and is not carried —
 *       only the remaining fuel ticks survive.</li>
 *   <li>{@code command} / {@code successCount} / {@code lastOutput} — command minecarts</li>
 *   <li>{@code spawnedType} / delay / ranges / counts — spawner minecarts</li>
 * </ul>
 *
 * <p>{@code getMinecartMaterial} is not serialized: the type key is authoritative.
 */
public final class MinecartSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "minecart";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof Minecart;
    }

    @Override
    public kz.bejiihiu.dimensions.common.model.EntityPayloadKind kindOf(Entity entity) {
        Minecart cart = (Minecart) entity;
        if (cart instanceof org.bukkit.entity.minecart.StorageMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_CHEST;
        }
        if (cart instanceof PoweredMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_FURNACE;
        }
        if (cart instanceof HopperMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_HOPPER;
        }
        if (cart instanceof CommandMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_COMMAND;
        }
        if (cart instanceof org.bukkit.entity.minecart.ExplosiveMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_TNT;
        }
        if (cart instanceof SpawnerMinecart) {
            return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART_SPAWNER;
        }
        return kz.bejiihiu.dimensions.common.model.EntityPayloadKind.MINECART;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        Minecart minecart = (Minecart) entity;
        JsonObject obj = readBase(entity);
        FieldCodec.putInt(obj, "minecartDamage", (int) minecart.getDamage());
        obj.addProperty("minecartMaxSpeed", minecart.getMaxSpeed());
        FieldCodec.putBoolean(obj, "slowWhenEmpty", minecart.isSlowWhenEmpty());
        obj.add("derailedVelocityMod", FieldCodec.velocity(minecart.getDerailedVelocityMod()));
        obj.add("flyingVelocityMod", FieldCodec.velocity(minecart.getFlyingVelocityMod()));
        obj.add("displayBlock", FieldCodec.blockData(minecart.getDisplayBlockData()));
        FieldCodec.putInt(obj, "displayBlockOffset", minecart.getDisplayBlockOffset());

        if (minecart instanceof InventoryHolder holder) {
            obj.add("contents", FieldCodec.slots(holder.getInventory().getContents()));
        }
        if (minecart instanceof HopperMinecart hopper) {
            FieldCodec.putBoolean(obj, "hopperEnabled", hopper.isEnabled());
        }
        if (minecart instanceof PoweredMinecart powered) {
            FieldCodec.putInt(obj, "fuel", powered.getFuel());
            obj.addProperty("pushX", powered.getPushX());
            obj.addProperty("pushZ", powered.getPushZ());
        }
        if (minecart instanceof CommandMinecart command) {
            obj.addProperty("command", command.getCommand());
            FieldCodec.putInt(obj, "successCount", command.getSuccessCount());
            String lastOutput = FieldCodec.name(command.lastOutput());
            if (lastOutput != null) {
                obj.addProperty("lastOutput", lastOutput);
            }
        }
        if (minecart instanceof SpawnerMinecart spawner) {
            if (spawner.getSpawnedType() != null) {
                obj.addProperty("spawnedType", spawner.getSpawnedType().getKey().getKey());
            }
            // spawned-item (trial spawners) has a setter but no getter in the public API — not carried
            FieldCodec.putInt(obj, "delay", spawner.getDelay());
            FieldCodec.putInt(obj, "minSpawnDelay", spawner.getMinSpawnDelay());
            FieldCodec.putInt(obj, "maxSpawnDelay", spawner.getMaxSpawnDelay());
            FieldCodec.putInt(obj, "spawnCount", spawner.getSpawnCount());
            FieldCodec.putInt(obj, "maxNearbyEntities", spawner.getMaxNearbyEntities());
            FieldCodec.putInt(obj, "requiredPlayerRange", spawner.getRequiredPlayerRange());
            FieldCodec.putInt(obj, "spawnRange", spawner.getSpawnRange());
        }
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        Minecart minecart = (Minecart) entity;
        writeBase(minecart, fields, metrics);
        if (fields.has("minecartMaxSpeed") && fields.get("minecartMaxSpeed").isJsonPrimitive()) {
            minecart.setMaxSpeed(fields.get("minecartMaxSpeed").getAsDouble());
        }
        minecart.setSlowWhenEmpty(FieldCodec.getBoolean(fields, "slowWhenEmpty", false));
        if (fields.has("derailedVelocityMod")) {
            minecart.setDerailedVelocityMod(FieldCodec.velocity(fields.getAsJsonObject("derailedVelocityMod")));
        }
        if (fields.has("flyingVelocityMod")) {
            minecart.setFlyingVelocityMod(FieldCodec.velocity(fields.getAsJsonObject("flyingVelocityMod")));
        }
        if (fields.has("displayBlock")) {
            org.bukkit.block.data.BlockData blockData = FieldCodec.blockData(fields.getAsJsonObject("displayBlock"));
            minecart.setDisplayBlockData(blockData);
        }
        minecart.setDisplayBlockOffset(FieldCodec.getInt(fields, "displayBlockOffset", 0));

        if (minecart instanceof InventoryHolder holder && fields.has("contents")) {
            org.bukkit.inventory.ItemStack[] contents =
                    FieldCodec.slots(fields.getAsJsonArray("contents"), holder.getInventory().getSize());
            for (int i = 0; i < contents.length; i++) {
                if (contents[i] != null) {
                    holder.getInventory().setItem(i, contents[i]);
                }
            }
        }
        if (minecart instanceof HopperMinecart hopper) {
            hopper.setEnabled(FieldCodec.getBoolean(fields, "hopperEnabled", true));
        }
        if (minecart instanceof PoweredMinecart powered) {
            powered.setFuel(Math.max(0, FieldCodec.getInt(fields, "fuel", 0)));
            if (fields.has("pushX") && fields.get("pushX").isJsonPrimitive()) {
                powered.setPushX(fields.get("pushX").getAsDouble());
            }
            if (fields.has("pushZ") && fields.get("pushZ").isJsonPrimitive()) {
                powered.setPushZ(fields.get("pushZ").getAsDouble());
            }
        }
        if (minecart instanceof CommandMinecart command) {
            String commandText = FieldCodec.getString(fields, "command");
            if (commandText != null) {
                command.setCommand(commandText);
            }
            command.setSuccessCount(FieldCodec.getInt(fields, "successCount", 0));
            net.kyori.adventure.text.Component lastOutput = FieldCodec.name(fields, "lastOutput");
            if (lastOutput != null) {
                command.lastOutput(lastOutput);
            }
        }
        if (minecart instanceof SpawnerMinecart spawner) {
            String spawnedType = FieldCodec.getString(fields, "spawnedType");
            if (spawnedType != null) {
                org.bukkit.entity.EntityType type = org.bukkit.Registry.ENTITY_TYPE.get(
                        org.bukkit.NamespacedKey.minecraft(spawnedType));
                if (type != null) {
                    spawner.setSpawnedType(type);
                } else {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            spawner.setDelay(FieldCodec.getInt(fields, "delay", 0));
            spawner.setMinSpawnDelay(FieldCodec.getInt(fields, "minSpawnDelay", spawner.getMinSpawnDelay()));
            spawner.setMaxSpawnDelay(FieldCodec.getInt(fields, "maxSpawnDelay", spawner.getMaxSpawnDelay()));
            spawner.setSpawnCount(FieldCodec.getInt(fields, "spawnCount", spawner.getSpawnCount()));
            spawner.setMaxNearbyEntities(FieldCodec.getInt(fields, "maxNearbyEntities", spawner.getMaxNearbyEntities()));
            spawner.setRequiredPlayerRange(FieldCodec.getInt(fields, "requiredPlayerRange", spawner.getRequiredPlayerRange()));
            spawner.setSpawnRange(FieldCodec.getInt(fields, "spawnRange", spawner.getSpawnRange()));
        }
        minecart.setDamage(Math.max(0.0, Math.min(40.0, (double) FieldCodec.getInt(fields, "minecartDamage", 0))));
    }
}
